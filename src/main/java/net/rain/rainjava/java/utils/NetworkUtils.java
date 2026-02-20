package net.rain.rainjava.java.util;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * 强大的网络通信工具类 - 简化数据包创建和发送
 * 
 * 使用方式1 - 继承SimplePacket:
 *   class MyPacket extends NetworkUtils.SimplePacket {
 *       String data;
 *       int value;
 *       
 *       public void write(FriendlyByteBuf buf) {
 *           buf.writeUtf(data);
 *           buf.writeInt(value);
 *       }
 *       
 *       public void read(FriendlyByteBuf buf) {
 *           data = buf.readUtf();
 *           value = buf.readInt();
 *       }
 *       
 *       public void handle(ServerPlayer player) {
 *           // 处理逻辑
 *       }
 *   }
 *   
 *   // 注册: NetworkUtils.register(MyPacket.class, MyPacket::new);
 *   // 发送: new MyPacket().sendToServer();
 *
 * 使用方式2 - 使用PacketBuilder快速创建:
 *   NetworkUtils.createPacket()
 *       .writeString("data")
 *       .writeInt(42)
 *       .onServerReceive((buf, player) -> {
 *           String data = buf.readString();
 *           int value = buf.readInt();
 *           // 处理逻辑
 *       })
 *       .build()
 *       .sendToAllPlayers();
 */
public class NetworkUtils {
    
    private static SimpleChannel CHANNEL;
    private static int packetId = 0;
    private static final String PROTOCOL_VERSION = "1";
    
    /**
     * 初始化网络通道
     */
    public static void init(String modid) {
        CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(modid, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
        );
    }
    
    // ==================== 简化的数据包基类 ====================
    
    /**
     * 简单数据包基类 - 自动处理编码/解码/发送
     */
    public static abstract class SimplePacket {
        
        /**
         * 写入数据到缓冲区
         */
        public abstract void write(FriendlyByteBuf buf);
        
        /**
         * 从缓冲区读取数据
         */
        public abstract void read(FriendlyByteBuf buf);
        
        /**
         * 在服务端处理（如果是客户端发送的）
         */
        public void handleServer(ServerPlayer player) {}
        
        /**
         * 在客户端处理（如果是服务端发送的）
         */
        public void handleClient() {}
        
        // 便捷发送方法
        public void sendToServer() {
            CHANNEL.sendToServer(this);
        }
        
        public void sendToPlayer(ServerPlayer player) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), this);
        }
        
        public void sendToAllPlayers() {
            CHANNEL.send(PacketDistributor.ALL.noArg(), this);
        }
        
        public void sendToNearby(ServerPlayer origin, double radius) {
            PacketDistributor.TargetPoint point = new PacketDistributor.TargetPoint(
                origin.getX(), origin.getY(), origin.getZ(), 
                radius, origin.level().dimension()
            );
            CHANNEL.send(PacketDistributor.NEAR.with(() -> point), this);
        }
        
        public void sendToDimension(ServerPlayer player) {
            CHANNEL.send(PacketDistributor.DIMENSION.with(() -> player.level().dimension()), this);
        }
    }
    
    /**
     * 注册SimplePacket类型的数据包
     */
    public static <T extends SimplePacket> void register(Class<T> clazz, Supplier<T> factory) {
        // 客户端到服务端
        CHANNEL.messageBuilder(clazz, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder((msg, buf) -> msg.write(buf))
                .decoder(buf -> {
                    T packet = factory.get();
                    packet.read(buf);
                    return packet;
                })
                .consumerMainThread((msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        ServerPlayer player = ctx.get().getSender();
                        if (player != null) {
                            msg.handleServer(player);
                        }
                    });
                    ctx.get().setPacketHandled(true);
                })
                .add();
        
        // 服务端到客户端
        CHANNEL.messageBuilder(clazz, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((msg, buf) -> msg.write(buf))
                .decoder(buf -> {
                    T packet = factory.get();
                    packet.read(buf);
                    return packet;
                })
                .consumerMainThread((msg, ctx) -> {
                    ctx.get().enqueueWork(() -> msg.handleClient());
                    ctx.get().setPacketHandled(true);
                })
                .add();
    }
    
    // ==================== PacketBuilder - 快速创建数据包 ====================
    
    /**
     * 创建快速数据包构建器
     */
    public static PacketBuilder createPacket() {
        return new PacketBuilder();
    }
    
    public static class PacketBuilder {
        private final FriendlyByteBuf tempBuf;
        private PacketHandler serverHandler;
        private ClientPacketHandler clientHandler;
        
        private PacketBuilder() {
            this.tempBuf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        }
        
        // 写入方法
        public PacketBuilder writeString(String str) {
            tempBuf.writeUtf(str);
            return this;
        }
        
        public PacketBuilder writeInt(int value) {
            tempBuf.writeInt(value);
            return this;
        }
        
        public PacketBuilder writeLong(long value) {
            tempBuf.writeLong(value);
            return this;
        }
        
        public PacketBuilder writeFloat(float value) {
            tempBuf.writeFloat(value);
            return this;
        }
        
        public PacketBuilder writeDouble(double value) {
            tempBuf.writeDouble(value);
            return this;
        }
        
        public PacketBuilder writeBoolean(boolean value) {
            tempBuf.writeBoolean(value);
            return this;
        }
        
        public PacketBuilder writeBytes(byte[] bytes) {
            tempBuf.writeBytes(bytes);
            return this;
        }
        
        // 处理器
        public PacketBuilder onServerReceive(PacketHandler handler) {
            this.serverHandler = handler;
            return this;
        }
        
        public PacketBuilder onClientReceive(ClientPacketHandler handler) {
            this.clientHandler = handler;
            return this;
        }
        
        /**
         * 构建数据包
         */
        public QuickPacket build() {
            byte[] data = new byte[tempBuf.readableBytes()];
            tempBuf.readBytes(data);
            return new QuickPacket(data, serverHandler, clientHandler);
        }
        
        @FunctionalInterface
        public interface PacketHandler {
            void handle(FriendlyByteBuf buf, ServerPlayer player);
        }
        
        @FunctionalInterface
        public interface ClientPacketHandler {
            void handle(FriendlyByteBuf buf);
        }
    }
    
    /**
     * 快速数据包 - 由PacketBuilder创建
     */
    public static class QuickPacket extends SimplePacket {
        private byte[] data;
        private final PacketBuilder.PacketHandler serverHandler;
        private final PacketBuilder.ClientPacketHandler clientHandler;
        
        private QuickPacket(byte[] data, PacketBuilder.PacketHandler serverHandler, 
                           PacketBuilder.ClientPacketHandler clientHandler) {
            this.data = data;
            this.serverHandler = serverHandler;
            this.clientHandler = clientHandler;
        }
        
        // 无参构造器供反序列化使用
        public QuickPacket() {
            this(new byte[0], null, null);
        }
        
        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeInt(data.length);
            buf.writeBytes(data);
        }
        
        @Override
        public void read(FriendlyByteBuf buf) {
            int length = buf.readInt();
            data = new byte[length];
            buf.readBytes(data);
        }
        
        @Override
        public void handleServer(ServerPlayer player) {
            if (serverHandler != null) {
                FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data));
                serverHandler.handle(buf, player);
            }
        }
        
        @Override
        public void handleClient() {
            if (clientHandler != null) {
                FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data));
                clientHandler.handle(buf);
            }
        }
    }
    
    // 静态注册QuickPacket
    static {
        registerQuickPacket();
    }
    
    /**
     * 注册QuickPacket（必须在init后调用一次）
     */
    public static void registerQuickPacket() {
        register(QuickPacket.class, QuickPacket::new);
    }
}