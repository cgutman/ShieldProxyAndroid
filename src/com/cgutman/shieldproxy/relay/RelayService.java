package com.cgutman.shieldproxy.relay;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.UnknownHostException;

import org.xbill.DNS.ARecord;
import org.xbill.DNS.Message;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;

import com.cgutman.shieldproxy.Configuration;
import com.cgutman.shieldproxy.R;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.widget.Toast;

public class RelayService extends Service implements Runnable {
	
	public static final short MDNS_PORT = 5353;
	public static InetAddress MDNS_ADDRESS;
	public static final int REPLY_TIMEOUT = 3000;
	
	private MdnsBinder binder = new MdnsBinder();
	
	private MulticastSocket msock;
	private SimpleResolver resolver;
	private DatagramSocket resolverSocket;
	
	public static final int FAILURES_FOR_WARNING = 5;
	private int consecutiveDnsFailures;
	private boolean displayedWarning;
	
	private String peer;
	private int port;
	
	private Handler handler;
	
	private Listener listener;
	
	static {
		try {
			MDNS_ADDRESS = InetAddress.getByName("224.0.0.251");
		} catch (UnknownHostException e) {
			MDNS_ADDRESS = null;
		}
	}
	
	public interface Listener {
		public void onRelayException(Exception e);
	}
	
	public class MdnsBinder extends Binder {
		public void startRelay() throws IOException
		{
			if (isRunning())
				throw new IllegalStateException("Relay already running");
			
			resolverSocket = new DatagramSocket();
			resolverSocket.setSoTimeout(RelayService.REPLY_TIMEOUT);
			
			msock = new MulticastSocket(RelayService.MDNS_PORT);
			msock.setLoopbackMode(false);
			msock.joinGroup(RelayService.MDNS_ADDRESS);
			
			Intent intent = new Intent(RelayService.this, Configuration.class);
			PendingIntent contentIntent = PendingIntent.getActivity(getApplicationContext(),
					0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
			
			Notification.Builder nb = new Notification.Builder(getApplicationContext());
			nb.setContentTitle("Shield Relay Running")
			.setContentText("Relaying to "+peer+":"+port)
			.setSmallIcon(R.drawable.ic_launcher)
			.setContentIntent(contentIntent);
			startForeground(1, nb.build());

			startRelayThread();
		}
		
		public boolean isRunning()
		{
			return msock != null;
		}
		
		public String getRelayPeer()
		{
			return peer;
		}
		
		public int getRelayPort()
		{
			return port;
		}
		
		public void setListener(RelayService.Listener listener)
		{
			RelayService.this.listener = listener;
		}
		
		public void configureRelay(String peer, int port)
		{
			if (isRunning())
				throw new IllegalStateException("Relay still running");
			
			RelayService.this.peer = peer;
			RelayService.this.port = port;
		}
		
		public void stopRelay()
		{
			RelayService.this.cleanupThread(null);
		}
	}
	
	public Thread startRelayThread()
	{
		System.out.println("Starting MDNS relay thread");
		Thread t = new Thread(this);
		t.start();
		return t;
	}
	
	@Override
	public void onCreate() {
		super.onCreate();
		
		handler = new Handler();
	}

	@Override
	public IBinder onBind(Intent arg0) {
		return binder;
	}
	
	private void cleanupThread(final Exception e)
	{	
		stopForeground(true);
		
		if (msock != null)
		{
			msock.close();
			msock = null;
		}
		
		if (resolverSocket != null)
		{
			resolverSocket.close();
			resolverSocket = null;
		}
		
		resolver = null;
		
		// We need to wait to notify until after cleanup
		if (e != null && e.getMessage() != null)
		{
			handler.post(new Runnable() {
				@Override
				public void run() {
					Toast.makeText(getApplicationContext(), e.getMessage(), Toast.LENGTH_LONG).show();
					
					if (listener != null) {
						listener.onRelayException(e);
					}
				}
			});
		}
	}
	
	private void notifyDnsFailure()
	{
		consecutiveDnsFailures++;
		
		if (!displayedWarning && (consecutiveDnsFailures % RelayService.FAILURES_FOR_WARNING) == 0)
		{
			displayedWarning = true;
			handler.post(new Runnable() {
				@Override
				public void run() {
					Toast.makeText(getApplicationContext(),
							"We haven't received any DNS responses. Is the relay running on your PC?",
							Toast.LENGTH_LONG).show();
				}
			});
		}
	}
	
	private void notifyDnsSuccess()
	{
		consecutiveDnsFailures = 0;
	}

	@Override
	public void run() {
		byte[] data = new byte[1500];
		
		try {
			resolver = new SimpleResolver(peer);
			resolver.setPort(port);
		} catch (UnknownHostException e) {
			cleanupThread(e);
			return;
		}
		
		System.out.println("Starting receiving loop");
		for (;;)
		{
			DatagramPacket recvPacket = new DatagramPacket(data, data.length);
			
			// Receive a MDNS packet
			try {
				msock.receive(recvPacket);
			} catch (IOException e) {
				cleanupThread(e);
				return;
			}
			
			System.out.println("Received "+recvPacket.getLength()+" bytes from "+recvPacket.getAddress());
			
			// Decode it
			Message message;
			try {
				message = MdnsParser.parseDnsMessage(recvPacket);
			} catch (IOException e) {
				e.printStackTrace();
				continue;
			}
		
			// Check if this is the Shield MDNS query
			Record question = message.getQuestion();
			if (question != null)
			{
				if (question.getName().toString().equals(NvStreamProtocol.NVSTREAM_MDNS_QUERY))
				{
					System.out.println("Forwarding Shield MDNS query to "+peer+":"+port);
					
					// Send the MDNS query to the peer
					DatagramPacket forwardedPacket = new DatagramPacket(recvPacket.getData(), recvPacket.getLength());
					try {
						forwardedPacket.setAddress(InetAddress.getByName(peer));
						forwardedPacket.setPort(port);
						resolverSocket.send(forwardedPacket);
					} catch (IOException e) {
						e.printStackTrace();
						continue;
					}
					
					// Receive the MDNS reply from the peer
					DatagramPacket replyPacket;
					Message reply;
					for (;;)
					{
						replyPacket = new DatagramPacket(data, data.length);
						try {
							resolverSocket.receive(replyPacket);
						} catch (InterruptedIOException e) {
							System.out.println("DNS request timed out");
							reply = null;
							break;
						} catch (IOException e) {
							cleanupThread(e);
							return;
						}
												
						// Parse the reply
						try {
							reply = MdnsParser.parseDnsMessage(replyPacket);
						} catch (IOException e) {
							e.printStackTrace();
							continue;
						}
												
						// Check if this is the reply we want
						Record[] records = reply.getSectionArray(Section.ANSWER);
						if (records == null || records.length != 1)
							continue;
						
						// This should match our query
						if (records[0].getName().toString().equals(NvStreamProtocol.NVSTREAM_MDNS_QUERY))
							break;
					}
					
					// Make sure we got a packet back
					if (reply == null)
					{
						notifyDnsFailure();
						continue;
					}
					else
					{
						notifyDnsSuccess();
					}
					
					System.out.println("Received reply from "+replyPacket.getSocketAddress());
					
					// Modify the reply authority records
					Record[] records = reply.getSectionArray(Section.ADDITIONAL);
					reply.removeAllRecords(Section.ADDITIONAL);
					System.out.println("Found "+records.length+" additional records");
					for (int i = 0; i < records.length; i++)
					{
						Record newRecord;
						
						if (records[i].getType() == Type.A)
						{
							System.out.println("Patching A record: "+
									((ARecord)records[i]).getAddress()+
									" -> "+replyPacket.getAddress());
							newRecord = new ARecord(records[i].getName(),
									records[i].getDClass(),
									records[i].getTTL(),
									replyPacket.getAddress());
						}
						else if ((records[i].getType() == Type.AAAA) ||
								 (records[i].getType() == Type.A6))
						{
							// Drop these IPv6 records
							System.out.println("Dropping AAAA/A6 record");
							newRecord = null;
						}
						else
						{
							// Keep the others unmodified
							newRecord = records[i];
						}
						
						if (newRecord != null)
						{
							reply.addRecord(newRecord, Section.ADDITIONAL);
						}
					}
					
					System.out.println("Sending modified reply to local multicast group");
					
					// Forward the reply to the local multicast group
					byte[] wireReply = reply.toWire();
					DatagramPacket sendPacket = new DatagramPacket(wireReply, wireReply.length);
					sendPacket.setAddress(RelayService.MDNS_ADDRESS);
					sendPacket.setPort(MDNS_PORT);
					try {
						msock.send(sendPacket);
					} catch (IOException e) {
						e.printStackTrace();
						continue;
					}
				}
			}
		}
	}
}
