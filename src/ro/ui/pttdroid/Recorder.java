package ro.ui.pttdroid;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Random;

import ro.ui.pttdroid.codecs.Speex;
import ro.ui.pttdroid.settings.AudioSettings;
import ro.ui.pttdroid.settings.CommSettings;
import ro.ui.pttdroid.util.AudioParams;
import ro.ui.pttdroid.util.PhoneIPs;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder.AudioSource;
import android.util.Log;

public class Recorder extends Thread {
	
	private final int SO_TIMEOUT = 0;
	
	private AudioRecord recorder;
	/*
	 * True if thread is running, false otherwise.
	 * This boolean is used for internal synchronization.
	 */
	private boolean isRunning = false;	
	/*
	 * True if thread is safely stopped.
	 * This boolean must be false in order to be able to start the thread.
	 * After changing it to true the thread is finished, without the ability to start it again.
	 */
	private boolean isFinishing = false;
	
	private DatagramSocket socket;
	private DatagramPacket packet;
	
	private short[] pcmFrame = new short[AudioParams.FRAME_SIZE + offsetInShorts];
	private byte[] encodedFrame;
	
	/*
	 * Frame sequence number.  Reset to 1 between transmissions.
	 */
	private int seqNum;
	
	/*
	 * Offset in bytes for leaving room for a header containing the seqNum.
	 */
	public static int offsetInBytes = 4;
	public static int offsetInShorts = offsetInBytes/2;
	
	/*
	 * Set to true FOR TESTING PURPOSES ONLY
	 */
	private static boolean introduceFakeLosses = true;
	
	public void run() {
		// Set audio specific thread priority
		android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
		
		while(!isFinishing()) {		
			init();
			while(isRunning()) {
				
				try {		
					ByteBuffer target = ByteBuffer.wrap(encodedFrame);
					byte[] headerBytes = ByteBuffer.allocate(offsetInBytes).putInt(seqNum).array();
					
					// Read PCM from the microphone buffer & encode it
					if(AudioSettings.useSpeex()==AudioSettings.USE_SPEEX) {
						// read data into pcmFrame
						int readStatus = recorder.read(pcmFrame, 0, AudioParams.FRAME_SIZE);
						
						Log.i ("Read status: ", String.format("%d", readStatus));
						
						// encode audio in pcmFrame into encodedFrame to be sent in datagram
						byte[] audioData = new byte[Speex.getEncodedSize(AudioSettings.getSpeexQuality())];
						Speex.encode(pcmFrame, audioData);	
						
						target.put(headerBytes);
						target.put(audioData);
					}
					else {
						target.put(headerBytes);
						recorder.read(encodedFrame, offsetInBytes, AudioParams.FRAME_SIZE_IN_BYTES);						
					}
					
					if (introduceFakeLosses) {
						Random randomGeneator = new Random();
						// this should be 25 percent loss
						if (randomGeneator.nextDouble() > 0.75) {
							seqNum += 2; // introduce fake loss
						} else {
							seqNum++;
						}
					} else {
					    seqNum++;
					}
					// Send encoded frame packed within an UDP datagram
					socket.send(packet);
				}
				catch(IOException e) {
					Log.d("Recorder", e.toString());
				}	
			}		
		
			release();	
			/*
			 * While is not running block the thread.
			 * By doing it, CPU time is saved.
			 */
			synchronized(this) {
				try {	
					if(!isFinishing())
						this.wait();
				}
				catch(InterruptedException e) {
					Log.d("Recorder", e.toString());
				}
			}					
		}							
	}
	
	private void init() {				
		try {	    	
			seqNum = 1;
			
			PhoneIPs.load();
			
			socket = new DatagramSocket();
			socket.setSoTimeout(SO_TIMEOUT);
			InetAddress addr = null;
			
			switch(CommSettings.getCastType()) {
			case CommSettings.BROADCAST:
				socket.setBroadcast(true);		
				addr = CommSettings.getBroadcastAddr();
				break;
			case CommSettings.MULTICAST:
				addr = CommSettings.getMulticastAddr();					
				break;
			case CommSettings.UNICAST:
				addr = CommSettings.getUnicastAddr();					
				break;
			}							
			
			// will use 4 bytes for storing seq number
			if(AudioSettings.useSpeex()==AudioSettings.USE_SPEEX) {
				encodedFrame = new byte[Speex.getEncodedSize(AudioSettings.getSpeexQuality()) + offsetInBytes];
			}
			else { 
				encodedFrame = new byte[AudioParams.FRAME_SIZE_IN_BYTES  + offsetInBytes];
			}
			
			packet = new DatagramPacket(
					encodedFrame, 
					encodedFrame.length, 
					addr, 
					CommSettings.getPort());

	    	recorder = new AudioRecord(
	    			AudioSource.MIC, 
	    			AudioParams.SAMPLE_RATE, 
	    			AudioFormat.CHANNEL_CONFIGURATION_MONO, 
	    			AudioParams.ENCODING_PCM_NUM_BITS, 
	    			AudioParams.RECORD_BUFFER_SIZE);
	    	
			recorder.startRecording();				
		}
		catch(SocketException e) {
			Log.d("Recorder", e.toString());
		}	
	}
	
	private void release() {			
		if(recorder!=null) {
			recorder.stop();
			recorder.release();
		}
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
	
}
