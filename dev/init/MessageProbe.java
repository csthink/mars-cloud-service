import java.nio.charset.StandardCharsets;
import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer;
import org.apache.rocketmq.client.consumer.PullResult;
import org.apache.rocketmq.client.consumer.PullStatus;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageQueue;

/** Host-side message round trip using the broker address returned by NameServer. */
public final class MessageProbe {
    public static void main(String[] args) throws Exception {
        String server = args[0], topic = args[1], value = args[2], group = args[3];
        if (args[4].equals("proxy")) {
            org.apache.rocketmq.remoting.netty.NettyRemotingClient client =
                new org.apache.rocketmq.remoting.netty.NettyRemotingClient(new org.apache.rocketmq.remoting.netty.NettyClientConfig());
            client.start();
            try {
                org.apache.rocketmq.remoting.protocol.header.GetMaxOffsetRequestHeader header =
                    new org.apache.rocketmq.remoting.protocol.header.GetMaxOffsetRequestHeader();
                header.setTopic(topic);
                header.setQueueId(0);
                header.setBrokerName("local-broker");
                org.apache.rocketmq.remoting.protocol.RemotingCommand response = client.invokeSync(server,
                    org.apache.rocketmq.remoting.protocol.RemotingCommand.createRequestCommand(
                        org.apache.rocketmq.remoting.protocol.RequestCode.GET_MAX_OFFSET, header), 10000);
                if (response.getCode() != 0) throw new IllegalStateException("Proxy rejected protocol request: " + response.getCode());
            } finally { client.shutdown(); }
            System.out.println("Proxy remoting request passed");
            return;
        }
        if (args[4].equals("write")) {
        DefaultMQProducer producer = new DefaultMQProducer(group + "-producer");
        producer.setNamesrvAddr(server);
        producer.setVipChannelEnabled(false);
        producer.start();
        try {
            producer.send(new Message(topic, value.getBytes(StandardCharsets.UTF_8)));
        } finally {
            producer.shutdown();
        }
        }
        DefaultMQPullConsumer consumer = new DefaultMQPullConsumer(group);
        consumer.setNamesrvAddr(server);
        consumer.setVipChannelEnabled(false);
        consumer.start();
        boolean found = false;
        try {
            for (MessageQueue queue : consumer.fetchSubscribeMessageQueues(topic)) {
                long offset = consumer.minOffset(queue);
                long end = consumer.maxOffset(queue);
                while (offset < end) {
                    PullResult result = consumer.pull(queue, "*", offset, 32);
                    if (result.getPullStatus() == PullStatus.FOUND) {
                        found |= result.getMsgFoundList().stream().anyMatch(message ->
                            value.equals(new String(message.getBody(), StandardCharsets.UTF_8)));
                    }
                    if (result.getNextBeginOffset() <= offset) break;
                    offset = result.getNextBeginOffset();
                }
            }
        } finally {
            consumer.shutdown();
        }
        if (!found) throw new IllegalStateException("Published message was not read back from the host");
        System.out.println("Host message round trip passed");
    }
}
