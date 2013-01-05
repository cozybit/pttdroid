package ro.ui.pttdroid;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.MulticastSocket;
import java.nio.ByteBuffer;

import ro.ui.pttdroid.codecs.Speex;
import ro.ui.pttdroid.settings.AudioSettings;
import ro.ui.pttdroid.settings.CommSettings;
import ro.ui.pttdroid.util.AudioParams;
import ro.ui.pttdroid.util.PhoneIPs;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

public class Player extends Thread {
		
	private AudioTrack player;
	private boolean isRunning = true;	
	private boolean isFinishing = false;	
	
	private DatagramSocket socket;
	private MulticastSocket multicastSocket;
	private DatagramPacket packet;	
	
	private short[] pcmFrame = new short[AudioParams.FRAME_SIZE];
	private byte[] encodedFrame;
	
	private int progress = 0;
	private int losses = 0;
	private int lastSeqNum;
	private int seqNum = 1;
				
	public void run() {
		android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);				 
		
		while(!isFinishing()) {			
			init();
			while(isRunning()) {
								
				try {				
					socket.receive(packet);	
					
					ByteBuffer buffer = ByteBuffer.wrap(encodedFrame);
					
					// If echo is turned off and I was the packet sender then skip playing
					if(AudioSettings.getEchoState()==AudioSettings.ECHO_OFF && PhoneIPs.contains(packet.getAddress()))
						continue;
					
					if(AudioSettings.useSpeex()==AudioSettings.USE_SPEEX) {
						// put encoded frame into a byte buffer
						byte[] audioData = new byte[encodedFrame.length - Recorder.offsetInBytes];

						// TODO this is lame, improve
						System.arraycopy(encodedFrame, Recorder.offsetInBytes, audioData, 0, encodedFrame.length - Recorder.offsetInBytes);
						
						int decodeStatus = Speex.decode(audioData, audioData.length, pcmFrame);
						Log.i ("Decode status: ", String.format("%d", decodeStatus));
					
						// only send the audio data to the player
						int playerStatus = player.write(pcmFrame, 0, AudioParams.FRAME_SIZE);
						Log.i ("Player write status: ", String.format("%d", playerStatus));
					}
					else {			
						player.write(encodedFrame, Recorder.offsetInBytes, AudioParams.FRAME_SIZE_IN_BYTES);
					}	
					
					// window the header
					byte[] header = new byte[Recorder.offsetInBytes];
					buffer.get(header, 0, Recorder.offsetInBytes);
					
					seqNum = ByteBuffer.wrap(header).getInt();
					int diff = seqNum - lastSeqNum;
					
					if (diff > 1) { // packet loss seen
						losses += diff - 1;
					}
					
					lastSeqNum = seqNum;
					
					// Make some progress
					makeProgress();
				}
				catch(IOException e) {
					Log.d("Player exception", e.toString());
				}	
			}		
		
			release();	
			synchronized(this) {
				try {	
					if(!isFinishing())
						this.wait();
				}
				catch(InterruptedException e) {
					Log.d("Player interrupted", e.toString());
				}
			}			
		}				
	}
	
	private void init() {	
		try {						
			player = new AudioTrack(
					AudioManager.STREAM_MUSIC, 
					AudioParams.SAMPLE_RATE, 
					AudioFormat.CHANNEL_CONFIGURATION_MONO, 
					AudioParams.ENCODING_PCM_NUM_BITS, 
					AudioParams.TRACK_BUFFER_SIZE, 
					AudioTrack.MODE_STREAM);	

			switch(CommSettings.getCastType()) {
			case CommSettings.BROADCAST:
				socket = new DatagramSocket(CommSettings.getPort());
				socket.setBroadcast(true);
				break;
			case CommSettings.MULTICAST:
				multicastSocket = new MulticastSocket(CommSettings.getPort());
				multicastSocket.joinGroup(CommSettings.getMulticastAddr());
				socket = multicastSocket;				
				break;
			case CommSettings.UNICAST:
				socket = new DatagramSocket(CommSettings.getPort());
				break;
			}							
			
			if(AudioSettings.useSpeex()==AudioSettings.USE_SPEEX) 
				encodedFrame = new byte[Speex.getEncodedSize(AudioSettings.getSpeexQuality()) + Recorder.offsetInBytes];
			else 
				encodedFrame = new byte[AudioParams.FRAME_SIZE_IN_BYTES + Recorder.offsetInBytes];
			
			packet = new DatagramPacket(encodedFrame, encodedFrame.length);			
			
			player.play();				
		}
		catch(IOException e) {
			Log.d("Player", e.toString());
		}		
	}
	
	private void release() {
		if(player!=null) {
			player.stop();		
			player.release();
		}
		
	}
	
	public void resetLosses() {
		losses = 0;
	}
	
	public int getLosses() {
		return losses;
	}
	
	public int getSeqNum() {
		return seqNum;
	}
	
	private synchronized void makeProgress() {
		progress++;
	}
	
	public synchronized int getProgress() {
		return progress;
	}
	
	public synchronized boolean isRunning() {
		return isRunning;
	}
	
	public synchronized void resumeAudio() {
		isRunning = true;
		this.notify();
	}
		
	public synchronized void pauseAudio() {
		isRunning = false;
		leaveGroup();
		socket.close();
	}
		
	public synchronized boolean isFinishing() {
		return isFinishing;
	}
	
	public synchronized void finish() {
		pauseAudio();
		isFinishing = true;
		this.notify();
	}
	
	private void leaveGroup() {
		try {
			multicastSocket.leaveGroup(CommSettings.getMulticastAddr());
		}
		catch(IOException e) {
			Log.d("Player", e.toString());
		}
		catch(NullPointerException e) {
			Log.d("Player", e.toString());
		}		
	}
		
}
