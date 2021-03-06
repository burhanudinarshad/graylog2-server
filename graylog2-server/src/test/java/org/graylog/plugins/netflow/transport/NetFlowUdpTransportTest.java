/**
 * This file is part of Graylog.
 *
 * Graylog is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.graylog.plugins.netflow.transport;

import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.graylog.plugins.netflow.codecs.NetflowV9CodecAggregator;
import org.graylog2.inputs.transports.NettyTransportConfiguration;
import org.graylog2.inputs.transports.netty.EventLoopGroupFactory;
import org.graylog2.plugin.LocalMetricRegistry;
import org.graylog2.plugin.configuration.Configuration;
import org.graylog2.plugin.inputs.MessageInput;
import org.graylog2.plugin.inputs.util.ThroughputCounter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class NetFlowUdpTransportTest {
    private EventLoopGroup eventLoopGroup;
    private EventLoopGroupFactory eventLoopGroupFactory;
    private NetFlowUdpTransport transport;

    @Before
    public void setUp() {
        final NettyTransportConfiguration nettyTransportConfiguration = new NettyTransportConfiguration("nio", "jdk", 1);
        eventLoopGroupFactory = new EventLoopGroupFactory(nettyTransportConfiguration);
        eventLoopGroup = new NioEventLoopGroup(1);
        transport = new NetFlowUdpTransport(
                Configuration.EMPTY_CONFIGURATION,
                eventLoopGroupFactory,
                nettyTransportConfiguration,
                new ThroughputCounter(eventLoopGroup),
                new LocalMetricRegistry());
        transport.setMessageAggregator(new NetflowV9CodecAggregator());
    }

    @After
    public void tearDown() {
        eventLoopGroup.shutdownGracefully();
    }

    @Test
    public void getChildChannelHandlersContainsCustomCodecAggregator() throws Exception {
        final LinkedHashMap<String, Callable<? extends ChannelHandler>> handlers = transport.getChannelHandlers(mock(MessageInput.class));
        assertThat(handlers)
                .containsKey("codec-aggregator")
                .doesNotContainKey("udp-datagram");

        final ChannelHandler channelHandler = handlers.get("codec-aggregator").call();
        assertThat(channelHandler).isInstanceOf(NetflowMessageAggregationHandler.class);
    }
}