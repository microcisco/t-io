package org.tio.core;

import java.io.IOException;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.tio.core.intf.Packet;
import org.tio.core.intf.Packet.Meta;
import org.tio.core.ssl.SslFacadeContext;
import org.tio.core.ssl.SslUtils;
import org.tio.core.stat.ChannelStat;
import org.tio.core.stat.IpStat;
import org.tio.core.task.CloseRunnable;
import org.tio.core.task.DecodeRunnable;
import org.tio.core.task.HandlerRunnable;
import org.tio.core.task.SendRunnable;
import org.tio.utils.json.Json;
import org.tio.utils.lock.SetWithLock;
import org.tio.utils.prop.MapWithLockPropSupport;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateTime;

/**
 *
 * @author tanyaowu
 * 2017年10月19日 上午9:39:46
 */
public abstract class ChannelContext extends MapWithLockPropSupport {
	private static Logger log = LoggerFactory.getLogger(ChannelContext.class);

	private static final String DEFAULT_ATTUBITE_KEY = "t-io-d-a-k";

	public static final String UNKNOWN_ADDRESS_IP = "$UNKNOWN";

	public static final AtomicInteger UNKNOWN_ADDRESS_PORT_SEQ = new AtomicInteger();

	public boolean isTraceClient = false;

	public boolean isTraceSynPacket = false;

	public boolean isReconnect = false;

	/**
	 * 一个packet所需要的字节数（用于应用告诉框架，下一次解码所需要的字节长度，省去冗余解码带来的性能损耗）
	 */
	public Integer packetNeededLength = null;

	//	private MapWithLock<String, Object> props = null;//

	public GroupContext groupContext = null;
	public DecodeRunnable decodeRunnable = null;
	public HandlerRunnable handlerRunnable = null;
	public SendRunnable sendRunnable = null;
	public final ReentrantReadWriteLock closeLock = new ReentrantReadWriteLock();
	private ReadCompletionHandler readCompletionHandler = null;//new ReadCompletionHandler(this);
	private WriteCompletionHandler writeCompletionHandler = null;//new WriteCompletionHandler(this);

	public SslFacadeContext sslFacadeContext;

	private int reconnCount = 0;//连续重连次数，连接成功后，此值会被重置0

	public String userid;

	private String token;

	private String bsId;

	private boolean isWaitingClose = false;

	private boolean isClosed = true;

	private boolean isRemoved = false;

	public final ChannelStat stat = new ChannelStat();

	/** The asynchronous socket channel. */
	public AsynchronousSocketChannel asynchronousSocketChannel;

	private String id = null;

	private Node clientNode;

	private String clientNodeTraceFilename;

	private Node serverNode;

	private Logger traceSynPacketLog = LoggerFactory.getLogger("tio-client-trace-syn-log");

	/**
	 * 该连接在哪些组中
	 */
	private SetWithLock<String> groups = null;

	public CloseRunnable closeRunnable;

	/**
	 *
	 * @param groupContext
	 * @param asynchronousSocketChannel
	 * @author tanyaowu
	 */
	public ChannelContext(GroupContext groupContext, AsynchronousSocketChannel asynchronousSocketChannel) {
		super();
		init(groupContext, asynchronousSocketChannel);

		if (groupContext.sslConfig != null) {
			try {
				SslFacadeContext sslFacadeContext = new SslFacadeContext(this);
				if (groupContext.isServer()) {
					sslFacadeContext.beginHandshake();
				}
			} catch (Exception e) {
				log.error("在开始SSL握手时发生了异常", e);
				Tio.close(this, "在开始SSL握手时发生了异常" + e.getMessage());
				return;
			}
		}
	}

	private void assignAnUnknownClientNode() {
		Node clientNode = new Node(UNKNOWN_ADDRESS_IP, UNKNOWN_ADDRESS_PORT_SEQ.incrementAndGet());
		setClientNode(clientNode);
	}

	/**
	 * 创建Node
	 * @param asynchronousSocketChannel
	 * @return
	 * @throws IOException
	 * @author tanyaowu
	 */
	public abstract Node createClientNode(AsynchronousSocketChannel asynchronousSocketChannel) throws IOException;

	/**
	 *
	 * @param obj
	 * @return
	 * @author tanyaowu
	 */
	@Override
	public boolean equals(Object obj) {
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		ChannelContext other = (ChannelContext) obj;
		return Objects.equals(other.hashCode(), this.hashCode());
	}

	public Object getAttribute() {
		return getAttribute(DEFAULT_ATTUBITE_KEY);
	}

	/**
	 * @return the remoteNode
	 */
	public Node getClientNode() {
		return clientNode;
	}

	/**
	 * @return the clientNodeTraceFilename
	 */
	public String getClientNodeTraceFilename() {
		return clientNodeTraceFilename;
	}

	public SetWithLock<String> getGroups() {
		return groups;
	}

	/**
	 * @return the id
	 */
	public String getId() {
		return id;
	}

	/**
	 * @return the readCompletionHandler
	 */
	public ReadCompletionHandler getReadCompletionHandler() {
		return readCompletionHandler;
	}

	/**
	 * @return the reConnCount
	 */
	public int getReconnCount() {
		return reconnCount;
	}

	/**
	 * @return the serverNode
	 */
	public Node getServerNode() {
		return serverNode;
	}

	public String getToken() {
		return token;
	}

	/**
	 * @return the writeCompletionHandler
	 */
	public WriteCompletionHandler getWriteCompletionHandler() {
		return writeCompletionHandler;
	}

	/**
	 *
	 * @return
	 * @author tanyaowu
	 */
	@Override
	public int hashCode() {
		if (StringUtils.isNotBlank(id)) {
			return this.id.hashCode();
		} else {
			return super.hashCode();
		}
	}

	public void init(GroupContext groupContext, AsynchronousSocketChannel asynchronousSocketChannel) {
		id = groupContext.getTioUuid().uuid();
		this.setGroupContext(groupContext);
		groupContext.ids.bind(this);
		this.setAsynchronousSocketChannel(asynchronousSocketChannel);
		this.readCompletionHandler = new ReadCompletionHandler(this);
		this.writeCompletionHandler = new WriteCompletionHandler(this);
	}

	/**
	 * @return the isClosed
	 */
	public boolean isClosed() {
		return isClosed;
	}

	/**
	 * @return the isRemoved
	 */
	public boolean isRemoved() {
		return isRemoved;
	}

	/**
	 * @return the isWaitingClose
	 */
	public boolean isWaitingClose() {
		return isWaitingClose;
	}

	/**
	 *
	 * @param obj PacketWithMeta or Packet
	 * @param isSentSuccess
	 * @author tanyaowu
	 */
	public void processAfterSent(Packet packet, Boolean isSentSuccess) {

		isSentSuccess = isSentSuccess == null ? false : isSentSuccess;
		//		if (isPacket) {
		//			packet = (Packet) obj;
		//		} else {
		//			packetWithMeta = (PacketWithMeta) obj;
		//			packet = packetWithMeta.getPacket();
		//			CountDownLatch countDownLatch = packetWithMeta.getCountDownLatch();
		//			traceBlockPacket(SynPacketAction.BEFORE_DOWN, packet, countDownLatch, null);
		//			countDownLatch.countDown();
		//		}
		Meta meta = packet.getMeta();
		if (meta != null) {
			CountDownLatch countDownLatch = meta.getCountDownLatch();
			traceBlockPacket(SynPacketAction.BEFORE_DOWN, packet, countDownLatch, null);
			countDownLatch.countDown();
		}

		try {
			if (log.isDebugEnabled()) {
				log.debug("{} 已经发送 {}", this, packet.logstr());
			}

			//非SSL or SSL已经握手
			if (this.sslFacadeContext == null || this.sslFacadeContext.isHandshakeCompleted()) {
				try {
					groupContext.getAioListener().onAfterSent(this, packet, isSentSuccess);
				} catch (Exception e) {
					log.error(e.toString(), e);
				}

				groupContext.groupStat.sentPackets.incrementAndGet();
				stat.sentPackets.incrementAndGet();

				if (groupContext.ipStats.durationList != null && groupContext.ipStats.durationList.size() > 0) {
					try {
						for (Long v : groupContext.ipStats.durationList) {
							IpStat ipStat = groupContext.ipStats.get(v, getClientNode().getIp());
							ipStat.getSentPackets().incrementAndGet();
							groupContext.getIpStatListener().onAfterSent(this, packet, isSentSuccess, ipStat);
						}
					} catch (Exception e) {
						log.error(e.toString(), e);
					}
				}
			}
		} catch (Throwable e) {
			log.error(e.toString(), e);
		}

		if (packet.getPacketListener() != null) {
			try {
				packet.getPacketListener().onAfterSent(this, packet, isSentSuccess);
			} catch (Throwable e) {
				log.error(e.toString(), e);
			}
		}

	}

	/**
	 * @param asynchronousSocketChannel the asynchronousSocketChannel to set
	 */
	public void setAsynchronousSocketChannel(AsynchronousSocketChannel asynchronousSocketChannel) {
		this.asynchronousSocketChannel = asynchronousSocketChannel;

		if (asynchronousSocketChannel != null) {
			try {
				Node clientNode = createClientNode(asynchronousSocketChannel);
				setClientNode(clientNode);
			} catch (IOException e) {
				log.info(e.toString(), e);
				assignAnUnknownClientNode();
			}
		} else {
			assignAnUnknownClientNode();
		}
	}

	/**
	 * 设置默认属性
	 * @param value
	 * @author tanyaowu
	 */
	public void setAttribute(Object value) {
		setAttribute(DEFAULT_ATTUBITE_KEY, value);
	}

	/**
	 * @param remoteNode the remoteNode to set
	 */
	private void setClientNode(Node clientNode) {
		this.clientNode = clientNode;

		if (this.groupContext.isShortConnection) {
			return;
		}

		if (this.clientNode != null) {
			groupContext.clientNodeMap.remove(this);
		}

		if (this.clientNode != null && !Objects.equals(UNKNOWN_ADDRESS_IP, this.clientNode.getIp())) {
			groupContext.clientNodeMap.put(this);
			clientNodeTraceFilename = StringUtils.replaceAll(clientNode.toString(), ":", "_");
		}
	}

	/**
	 * @param clientNodeTraceFilename the clientNodeTraceFilename to set
	 */
	public void setClientNodeTraceFilename(String clientNodeTraceFilename) {
		this.clientNodeTraceFilename = clientNodeTraceFilename;
	}

	/**
	 * @param isClosed the isClosed to set
	 */
	public void setClosed(boolean isClosed) {
		this.isClosed = isClosed;
		if (isClosed) {
			if (clientNode == null || !UNKNOWN_ADDRESS_IP.equals(clientNode.getIp())) {
				String before = this.toString();
				assignAnUnknownClientNode();
				log.info("关闭前{}, 关闭后{}", before, this);
			}
		}
	}

	/**
	 * @param groupContext the groupContext to set
	 */
	public void setGroupContext(GroupContext groupContext) {
		this.groupContext = groupContext;

		if (groupContext != null) {
			decodeRunnable = new DecodeRunnable(this);
			handlerRunnable = new HandlerRunnable(this, groupContext.tioExecutor);
			sendRunnable = new SendRunnable(this, groupContext.tioExecutor);
			closeRunnable = new CloseRunnable(this, groupContext.tioCloseExecutor);
			groupContext.connections.add(this);
		}
	}

	public void setGroups(SetWithLock<String> groups) {
		this.groups = groups;
	}

	public void setPacketNeededLength(Integer packetNeededLength) {
		this.packetNeededLength = packetNeededLength;
	}

	/**
	 * @param reConnCount the reConnCount to set
	 */
	public void setReconnCount(int reconnCount) {
		this.reconnCount = reconnCount;
	}

	public void setReconnect(boolean isReconnect) {
		this.isReconnect = isReconnect;
	}

	/**
	 * @param isRemoved the isRemoved to set
	 */
	public void setRemoved(boolean isRemoved) {
		this.isRemoved = isRemoved;
	}

	/**
	 * @param serverNode the serverNode to set
	 */
	public void setServerNode(Node serverNode) {
		this.serverNode = serverNode;
	}

	public void setSslFacadeContext(SslFacadeContext sslFacadeContext) {
		this.sslFacadeContext = sslFacadeContext;
	}

	public void setToken(String token) {
		this.token = token;
	}

	/**
	 * @param isTraceClient the isTraceClient to set
	 */
	public void setTraceClient(boolean isTraceClient) {
		this.isTraceClient = isTraceClient;
	}

	/**
	 * @param isTraceSynPacket the isTraceSynPacket to set
	 */
	public void setTraceSynPacket(boolean isTraceSynPacket) {
		this.isTraceSynPacket = isTraceSynPacket;
	}

	/**
	 * @param userid the userid to set
	 * 给框架内部用的，用户请勿调用此方法
	 */
	public void setUserid(String userid) {
		this.userid = userid;
	}

	/**
	 * @param isWaitingClose the isWaitingClose to set
	 */
	public void setWaitingClose(boolean isWaitingClose) {
		this.isWaitingClose = isWaitingClose;
	}

	@Override
	public String toString() {
		if (SslUtils.isSsl(this)) {
			return this.getClientNode().toString() + ", SslShakehanded:" + this.sslFacadeContext.isHandshakeCompleted();
		} else {
			return this.getClientNode().toString();
		}

	}

	/**
	 * 跟踪同步消息，主要是跟踪锁的情况，用于问题排查。
	 * @param synPacketAction
	 * @param packet
	 * @param extmsg
	 * @author tanyaowu
	 */
	public void traceBlockPacket(SynPacketAction synPacketAction, Packet packet, CountDownLatch countDownLatch, Map<String, Object> extmsg) {
		if (isTraceSynPacket) {
			ChannelContext channelContext = this;
			Map<String, Object> map = new HashMap<>(10);
			map.put("time", DateTime.now().toString(DatePattern.NORM_DATETIME_MS_FORMAT));
			map.put("c_id", channelContext.getId());
			map.put("c", channelContext.toString());
			map.put("action", synPacketAction);

			MDC.put("tio_client_syn", channelContext.getClientNodeTraceFilename());

			if (packet != null) {
				map.put("p_id", channelContext.getClientNode().getPort() + "_" + packet.getId()); //packet id
				map.put("p_respId", packet.getRespId());
				map.put("packet", packet.logstr());
			}

			if (countDownLatch != null) {
				map.put("countDownLatch", countDownLatch.hashCode() + " " + countDownLatch.getCount());
			}

			if (extmsg != null) {
				map.putAll(extmsg);
			}
			String logstr = Json.toJson(map);
			traceSynPacketLog.info(logstr);
			log.error(logstr);

		}
	}

	/**
	 * 跟踪消息
	 * @param channelAction
	 * @param packet
	 * @param extmsg
	 * @author tanyaowu
	 */
	public void traceClient(ChannelAction channelAction, Packet packet, Map<String, Object> extmsg) {
		if (isTraceClient) {
			this.groupContext.clientTraceHandler.traceChannel(this, channelAction, packet, extmsg);
		}
	}

	/**
	 * @return the bsId
	 */
	public String getBsId() {
		return bsId;
	}

	/**
	 * @param bsId the bsId to set
	 */
	public void setBsId(String bsId) {
		this.bsId = bsId;
	}
	
	public GroupContext getGroupContext() {
		return groupContext;
	}

	/**
	 * 是否是服务器端
	 * @return
	 * @author tanyaowu
	 */
	public abstract boolean isServer();
}
