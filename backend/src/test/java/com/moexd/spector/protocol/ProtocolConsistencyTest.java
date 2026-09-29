package com.moexd.spector.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协议契约测试：
 * 1. backend 与 velocity 两个模块中的 SpectorProtocol.java 必须逐字节一致（防止契约漂移）；
 * 2. 帧格式 VERSION, SUB, ...字段..., SECRET 的 UTF 编解码往返保持不变。
 */
class ProtocolConsistencyTest {

    private static final String RELATIVE_PATH = "src/main/java/com/moexd/spector/protocol/SpectorProtocol.java";

    private static byte[] readProtocolSource(String moduleDir) throws Exception {
        Path path = Paths.get(moduleDir, RELATIVE_PATH);
        assertTrue(Files.exists(path), "缺少协议常量文件: " + path.toAbsolutePath());
        return Files.readAllBytes(path);
    }

    @Test
    void backendAndVelocityProtocolsAreIdentical() throws Exception {
        byte[] backend = readProtocolSource(".");
        byte[] velocity = readProtocolSource("../velocity");
        assertArrayEquals(backend, velocity,
                "backend 与 velocity 模块的 SpectorProtocol.java 不一致，协议契约已漂移，必须两端同步修改");
    }

    @Test
    void protocolVersionIsNonBlank() {
        assertTrue(SpectorProtocol.VERSION != null && !SpectorProtocol.VERSION.isBlank());
    }

    @Test
    void frameRoundTrip() throws Exception {
        String secret = "test-secret";
        UUID observer = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeUTF(SpectorProtocol.VERSION);
        out.writeUTF(SpectorProtocol.PAIR);
        out.writeUTF("lobby");
        out.writeUTF(observer.toString());
        out.writeUTF(target.toString());
        out.writeUTF(SpectorProtocol.MODE_RANDOM);
        out.writeUTF("uuid1,uuid2");
        out.writeUTF(secret);

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bos.toByteArray()));
        assertEquals(SpectorProtocol.VERSION, in.readUTF());
        assertEquals(SpectorProtocol.PAIR, in.readUTF());
        assertEquals("lobby", in.readUTF());
        assertEquals(observer, UUID.fromString(in.readUTF()));
        assertEquals(target, UUID.fromString(in.readUTF()));
        assertEquals(SpectorProtocol.MODE_RANDOM, in.readUTF());
        assertEquals("uuid1,uuid2", in.readUTF());
        assertEquals(secret, in.readUTF());
        // 流必须恰好读完
        assertEquals(0, in.available());
    }

    @Test
    void utfEncodingIsUtf8Compatible() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        String chinese = "观察者";
        out.writeUTF(chinese);
        byte[] bytes = bos.toByteArray();
        // java DataOutputStream 的 writeUTF 为 modified UTF-8，此处仅验证非 ASCII 可往返
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        assertEquals(chinese, in.readUTF());
        assertTrue(new String(bytes, StandardCharsets.UTF_8).contains("观") || bytes.length > 2);
    }
}
