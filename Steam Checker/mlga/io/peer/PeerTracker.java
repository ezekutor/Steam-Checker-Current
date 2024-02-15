package mlga.io.peer;

import java.io.File;
import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;

import mlga.io.FileUtil;

/**
 * Class for background parsing Unity log files into pairing of UID:IP to enable persistent ratings past dynamic IP ranges.
 *
 * @author ShadowMoose
 */
public class PeerTracker implements Runnable {
	private File logDir = new File(new File(System.getenv("APPDATA")).getParentFile().getAbsolutePath() + "/");
	private static File peerFile = new File(FileUtil.getMlgaPath() + "peers.mlga");
	private static CopyOnWriteArrayList<IOPeer> peers = new CopyOnWriteArrayList<IOPeer>();
	private static boolean saving = false;
	private String uid = null;
	private boolean active = false;

	/**
	 * Creates a PeerTracker, which instantly loads the Peer List into memory.  <br>
	 * Calling {@link #start()} will launch the passive listening component, which
	 * will keep the Peer List updated as new logs are created.
	 */
	public PeerTracker() {

		// PeerSavers create emergency backups, so loop to check primary file, then attempt fallback if needed.
		for (int i = 0; i < 2; i++) {
			try {
				PeerReader ps = new PeerReader(FileUtil.getSaveName(peerFile, i));
				while (ps.hasNext())
					peers.add(ps.next());
				System.out.println("Loaded " + peers.size() + " tracked users!");

				if (i != 0) // If we had to check a backup, re-save the backup as the primary instantly.
					savePeers();

				break;
			} catch (Exception e) {
				e.printStackTrace();
				if (i == 0)
					System.err.println("No Peers file located! Checking backups!");
			}
		}
	}

	/** Launches this listener thread, in order to automatically update Peers. */
	public void start() {
		// Adding a listener to each Peer, or a clever callback, might be better.
		//    + Though, this method does cut down on file writes during times of many updates.
		Thread t = new Thread(this, "IOPeerSaver");
		t.setDaemon(true);
		t.start();
	}

	@Override
	public void run() {
		while (true) {
			if (peers.stream().anyMatch(p -> !p.saved)) {
				savePeers();
			}
			// Wait 100ms before rechecking Peers for changes.
			try {
				Thread.sleep(100);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * Deduplicate list of Peers by combining values from matching UIDs. <br>
	 * For UI purposes, it is potentially important that existing IOPeers within the list exist for the current runtime.
	 * As such, this deduplication is used for saving, so duplicates are culled for future sessions.  <br>
	 *
	 * @return
	 */
	private ArrayList<IOPeer> deduplicate() {
		ArrayList<IOPeer> unique = new ArrayList<IOPeer>();
		peers.forEach(p -> {
			boolean add = true;
			for (IOPeer u : unique) {
				if (p.hasUID() && p.getUID().equals(u.getUID())) {
					// If this UID is already assigned to a Peer in the Unique List,
					// append this Peer's data to the existing Peer, and skip adding this Peer to the Unique List.
					add = false;
					p.copyTo(u);
					break;
				}
			}
			if (add)
				unique.add(p);
		});

		return unique;
	}

	/**
	 * Run through all log files, checking for new Peers.
	 */
	public void checkLogs() {
		for (File f : logDir.listFiles()) {
			if (f != null) {
				if (f.isDirectory())
					continue;
				if (!f.getName().endsWith(".log"))
					continue;
				System.out.println(f.getName());
				processLog(f, false);
			}
		}
		System.out.println("Identified " + peers.size() + " unique user/ip combos!");
		active = false;
	}

	/**
	 * Attempts to save the list of IOPeers.  <br>
	 * Should be called whenever a Peer's information is updated.  <br><br>
	 * Since the Peer List is static between all instances of PeerTracker, this method may be called by anything.
	 *
	 * @return True if this save works. May not work if a save is already underway.
	 */
	private boolean savePeers() {
		if (saving) {
			// This type of check is less than ideal,
			// but if save is being called at the same time, the first instance should still save all listed IOPeers.
			System.err.println("Peer File is busy!");
			return false;
		}
		System.err.println("Saving peers!");
		// Flag that the save file is busy, to avoid thread shenanigans.
		saving = true;
		try {
			PeerSaver ps = new PeerSaver(peerFile);
			ps.save(deduplicate());
			saving = false;
			return true;
		} catch (Exception e) {
			e.printStackTrace();
		}
		saving = false;
		return false;
	}

	/**
	 * The main method of interfacing with the Peer List,
	 * this method either retrieves an existing IOPeer object which "owns" the given IP,
	 * or it returns the new IOPeer object generated - and containing - the new IP.
	 *
	 * @param ip
	 */
	public IOPeer getPeer(Inet4Address ip) {
		IOPeer ret = peers.stream().filter(p -> p.hasIP(ip)).findFirst().orElse(null);

		if (ret == null) {
			ret = new IOPeer();
			ret.addIP(ip);
			peers.add(ret);
		}

		return ret;
	}

	/**
	 * Iterates through the Log file, pairing UIDs and IPs that it can find,
	 * and adding them to the IOPeer list or updating existing IOPeers where missing info is found.
	 *
	 * @param f       The file to process.
	 * @param newFile If this file is new data, in a recently-created file.
	 */
	private void processLog(File f, final boolean newFile) {
	}
}
