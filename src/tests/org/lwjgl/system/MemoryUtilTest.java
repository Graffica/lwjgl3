package org.lwjgl.system;

import org.lwjgl.BufferUtils;
import org.testng.annotations.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import static org.lwjgl.system.MemoryUtil.*;
import static org.testng.Assert.*;

@Test
public class MemoryUtilTest {

	public void testMemSet() {
		ByteBuffer buffer = BufferUtils.createByteBuffer(32);
		for ( int i = 0; i < buffer.capacity(); i++ )
			buffer.put(i, (byte)i);

		memSet(nGetAddress(buffer), 0x7F, buffer.capacity());

		for ( int i = 0; i < buffer.capacity(); i++ )
			assertEquals(buffer.get(i), 0x7F);
	}

	public void testMemCopy() {
		ByteBuffer src = BufferUtils.createByteBuffer(32);
		ByteBuffer dst = BufferUtils.createByteBuffer(32);

		for ( int i = 0; i < src.capacity(); i++ )
			src.put(i, (byte)i);

		memCopy(memAddress(src), memAddress(dst), src.capacity());

		for ( int i = 0; i < src.capacity(); i++ )
			assertEquals(src.get(i), dst.get(i));
	}

	public void testJNINewBuffer() {
		ByteBuffer buffer = BufferUtils.createByteBuffer(32);
		for ( int i = 0; i < buffer.capacity(); i++ )
			buffer.put(i, (byte)i);

		long address = nGetAddress(buffer);
		assertTrue(address != 0L);

		ByteBuffer view = nNewBuffer(address + 8, 16);
		assertEquals(view.order(), ByteOrder.BIG_ENDIAN);
		for ( int i = 0; i < view.capacity(); i++ )
			assertEquals(view.get(i), buffer.get(i + 8));
	}

	public void testAddress() {
		ByteBuffer buffer = BufferUtils.createByteBuffer(32);
		for ( int i = 0; i < buffer.capacity(); i++ )
			buffer.put(i, (byte)i);

		long address = memAddress(buffer);
		assertTrue(address != 0L);

		buffer.position(8);
		buffer.limit(8 + 16);

		FloatBuffer floatView = buffer.slice().order(ByteOrder.nativeOrder()).asFloatBuffer();

		assertEquals(address + 8, memAddress(floatView));
	}

	public void testNewBuffer() {
		ByteBuffer buffer = BufferUtils.createByteBuffer(32);
		for ( int i = 0; i < buffer.capacity(); i++ )
			buffer.put(i, (byte)i);

		long address = memAddress(buffer);
		assertTrue(address != 0L);

		ByteBuffer view = memByteBuffer(address + 8, 16);
		assertEquals(view.order(), ByteOrder.nativeOrder());
		for ( int i = 0; i < view.capacity(); i++ )
			assertEquals(view.get(i), buffer.get(i + 8));
	}

}