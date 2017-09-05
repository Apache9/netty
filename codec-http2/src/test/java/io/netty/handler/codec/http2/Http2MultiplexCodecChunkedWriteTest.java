/*
 * Copyright 2017 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.netty.handler.codec.http2;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.stream.ChunkedInput;
import io.netty.handler.stream.ChunkedStream;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.concurrent.Promise;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class Http2MultiplexCodecChunkedWriteTest {

    private static byte[] testData = "Hello World".getBytes(StandardCharsets.UTF_8);

    private static NioEventLoopGroup group;

    private static Channel server;

    private static int port;

    private static class ChunkedHttp2DataFrame implements ChunkedInput<Http2DataFrame> {

        private final ChunkedInput<ByteBuf> in;

        public ChunkedHttp2DataFrame(ChunkedInput<ByteBuf> in) {
            this.in = in;
        }

        @Override
        public boolean isEndOfInput() throws Exception {
            return in.isEndOfInput();
        }

        @Override
        public void close() throws Exception {
            in.close();
        }

        @Override
        public Http2DataFrame readChunk(ChannelHandlerContext ctx) throws Exception {
            return readChunk(ctx.alloc());
        }

        @Override
        public Http2DataFrame readChunk(ByteBufAllocator allocator) throws Exception {
            ByteBuf buf = in.readChunk(allocator);
            return new DefaultHttp2DataFrame(buf, isEndOfInput());
        }

        @Override
        public long length() {
            return in.length();
        }

        @Override
        public long progress() {
            return in.progress();
        }

    }

    private static class ServerHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof Http2HeadersFrame) {
                ctx.write(new DefaultHttp2HeadersFrame(
                        new DefaultHttp2Headers().status(HttpResponseStatus.OK.codeAsText()),
                        false));
                ctx.writeAndFlush(new ChunkedHttp2DataFrame(
                        new ChunkedStream(new ByteArrayInputStream(testData), 1)));
            } else {
                ctx.fireChannelRead(msg);
            }
        }

    }

    private static class Receiver extends ChannelInboundHandlerAdapter {

        private final Promise<ByteBuf> data;

        private CompositeByteBuf buf;

        public Receiver(Promise<ByteBuf> data) {
            this.data = data;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof Http2HeadersFrame) {
                Http2HeadersFrame frame = (Http2HeadersFrame) msg;
                if (!frame.headers().status().equals(HttpResponseStatus.OK.codeAsText())) {
                    data.tryFailure(new IOException("Unexpected status: frame.headers().status()"));
                    return;
                }
                if (frame.isEndStream()) {
                    data.trySuccess(Unpooled.EMPTY_BUFFER);
                }
            } else if (msg instanceof Http2DataFrame) {
                Http2DataFrame frame = (Http2DataFrame) msg;
                if (buf == null) {
                    buf = new CompositeByteBuf(ctx.alloc(), true, Integer.MAX_VALUE);
                }
                buf.addComponent(true, frame.content());
                if (frame.isEndStream()) {
                    if (!data.trySuccess(buf)) {
                        buf.release();
                        buf = null;
                    }
                }
            }
        }

    }

    @BeforeClass
    public static void setUp() {
        group = new NioEventLoopGroup();
        server = new ServerBootstrap().channel(NioServerSocketChannel.class).group(group)
                .childHandler(new ChannelInitializer<Channel>() {

                    @Override
                    protected void initChannel(Channel ch) throws Exception {
                        ch.pipeline().addLast(Http2MultiplexCodecBuilder
                                .forServer(new ChannelInitializer<Channel>() {

                                    @Override
                                    protected void initChannel(Channel ch) throws Exception {
                                        ch.pipeline().addLast(new ChunkedWriteHandler(),
                                                new ServerHandler());
                                    }
                                }).frameLogger(new Http2FrameLogger(LogLevel.INFO, "server"))
                                .build());
                    }
                }).bind(0).syncUninterruptibly().channel();
        port = ((InetSocketAddress) server.localAddress()).getPort();

    }

    @AfterClass
    public static void tearDown() {
        if (server != null) {
            server.close();
        }
        if (group != null) {
            group.shutdownGracefully();
        }
    }

    @Test
    public void test() throws Exception {
        Channel client = new Bootstrap().group(group).channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<Channel>() {

                    @Override
                    protected void initChannel(Channel ch) throws Exception {
                        ch.pipeline()
                                .addLast(Http2MultiplexCodecBuilder
                                        .forClient(new TestChannelInitializer())
                                        .frameLogger(new Http2FrameLogger(LogLevel.INFO, "client"))
                                        .build());
                    }
                }).connect("localhost", port).syncUninterruptibly().channel();
        final Promise<ByteBuf> data = client.eventLoop().newPromise();
        Http2StreamChannel stream = new Http2StreamChannelBootstrap(client)
                .handler(new ChannelInitializer<Channel>() {

                    @Override
                    protected void initChannel(Channel ch) throws Exception {
                        ch.pipeline().addLast(new Receiver(data));
                    }

                }).open().syncUninterruptibly().getNow();
        stream.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()));
        assertEquals(new String(testData, StandardCharsets.UTF_8),
                data.syncUninterruptibly().getNow().toString(StandardCharsets.UTF_8));
    }
}
