package org.drivecast.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream

class ProtocolTest {

    @Test
    fun framesSurviveGarbageBeforeMagic() {
        val hello = Hello(1920, 720, 160, 30, 4_000_000)
        val bytes = "WARNING: linker DCv".toByteArray() + MAGIC +
            encodeFrame(Msg.HELLO, hello.encode()) + encodeFrame(Msg.BYE)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        input.skipToMagic()

        val f = input.readFrame()
        assertEquals(Msg.HELLO, f.type)
        assertEquals(hello, Hello.decode(f.payload))
        assertEquals(Msg.BYE, input.readFrame().type)
    }

    @Test
    fun messagesRoundTrip() {
        assertEquals(HelloAck(7), HelloAck.decode(HelloAck(7).encode()))
        assertEquals(Touch(2, 1919, 719), Touch.decode(Touch(2, 1919, 719).encode()))
        assertEquals(Key(1, 4), Key.decode(Key(1, 4).encode()))

        val csd = byteArrayOf(0, 0, 0, 1, 0x67, 0x42)
        VideoConfig.decode(VideoConfig(1280, 720, csd).encode()).run {
            assertEquals(listOf(1280, 720), listOf(width, height))
            assertArrayEquals(csd, this.csd)
        }

        val data = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)
        VideoFrame.decode(VideoFrame(123_456_789L, true, data).encode()).run {
            assertEquals(123_456_789L, ptsUs)
            assertEquals(true, keyframe)
            assertArrayEquals(data, this.data)
        }
    }
}
