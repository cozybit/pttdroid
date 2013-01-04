package ro.ui.pttdroid;

public class PlayHello extends Thread {

	public boolean runLoop = false;

	public void run() {

		android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
		
		while(true) {
			if (runLoop) {
				System.out.println("Hello thread running!");
				playHello();
			}
		}
	}

	public void playHello() {
		Main.resumeAudiofunc();
		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		Main.pauseAudiofunc();
		
		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	public boolean getRunLoop() {
		return runLoop;
	}

	public void setRunLoop(boolean runLoop) {
		this.runLoop = runLoop;
	}
}
