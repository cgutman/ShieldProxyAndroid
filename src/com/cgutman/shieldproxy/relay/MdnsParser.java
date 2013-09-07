package com.cgutman.shieldproxy.relay;

import java.io.IOException;
import java.net.DatagramPacket;

import org.xbill.DNS.Message;

public class MdnsParser {
	public static Message parseDnsMessage(DatagramPacket packet) throws IOException
	{
		byte[] data = new byte[packet.getLength()];
		System.arraycopy(packet.getData(), 0, data, 0, packet.getLength());
		return new Message(data);
	}
}
