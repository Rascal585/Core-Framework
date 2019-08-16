package com.openrsc.server;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.openrsc.server.event.rsc.ImmediateEvent;
import com.openrsc.server.login.LoginRequest;
import com.openrsc.server.login.LoginTask;
import com.openrsc.server.model.entity.player.Player;
import com.openrsc.server.net.ThrottleFilter;
import com.openrsc.server.sql.DatabasePlayerLoader;
import com.openrsc.server.util.rsc.LoginResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PlayerDatabaseExecutor extends ThrottleFilter implements Runnable {

	/**
	 * The asynchronous logger.
	 */
	private static final Logger LOGGER = LogManager.getLogger();

	private final ScheduledExecutorService scheduledExecutor;

	private Queue<LoginRequest> loadRequests = new ConcurrentLinkedQueue<LoginRequest>();

	private Queue<Player> saveRequests = new ConcurrentLinkedQueue<Player>();

	private Queue<Player> removeRequests = new ConcurrentLinkedQueue<Player>();

	private DatabasePlayerLoader database;

	private Boolean running	= false;

	private final Server server;
	public final Server getServer() {
		return server;
	}

	public PlayerDatabaseExecutor(Server server) {
		this.server = server;
		this.database = new DatabasePlayerLoader(getServer());
		scheduledExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder().setNameFormat(getServer().getName()+" : PlayerDataProcessorThread").build());
	}

	@Override
	public void run() {
		synchronized(running) {
			try {
				LoginRequest loginRequest = null;
				while ((loginRequest = loadRequests.poll()) != null) {
					int loginResponse = database.validateLogin(loginRequest);
					loginRequest.loginValidated(loginResponse);
					if ((loginResponse & 0x40) != LoginResponse.LOGIN_INSUCCESSFUL) {
						final Player loadedPlayer = database.loadPlayer(loginRequest);

						LoginTask loginTask = new LoginTask(loginRequest, loadedPlayer);
						getServer().getGameEventHandler().add(new ImmediateEvent(getServer().getWorld(), "Login Player") {
							@Override
							public void action() {
								loginTask.run();
							}
						});

					}
					//LOGGER.info("Processed login request for " + loginRequest.getUsername() + " response: " + loginResponse);
				}

				Player playerToSave = null;
				while ((playerToSave = saveRequests.poll()) != null) {
					getDatabase().savePlayer(playerToSave);
					//LOGGER.info("Saved player " + playerToSave.getUsername() + "");
				}

				Player playerToRemove = null;
				while ((playerToRemove = removeRequests.poll()) != null) {
					playerToRemove.remove();
					getServer().getWorld().getPlayers().remove(playerToRemove);
					//LOGGER.info("Removed player " + playerToRemove.getUsername() + "");
				}
			} catch (Throwable e) {
				LOGGER.catching(e);
			}
		}
	}


	public DatabasePlayerLoader getDatabase() {
		return database;
	}


	public void addLoginRequest(LoginRequest request) {
		loadRequests.add(request);
	}


	public void addSaveRequest(Player player) {
		saveRequests.add(player);
	}

	public void addRemoveRequest(Player player) {
		removeRequests.add(player);
	}

	public void start() {
		synchronized (running) {
			running = true;
			scheduledExecutor.scheduleAtFixedRate(this, 0, 50, TimeUnit.MILLISECONDS);
		}
	}

	public void stop() {
		synchronized (running) {
			running = false;
			scheduledExecutor.shutdown();
		}
	}

	public final boolean isRunning() {
		return running;
	}
}
