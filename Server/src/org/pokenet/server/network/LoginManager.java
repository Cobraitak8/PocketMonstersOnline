package org.pokenet.server.network;

import java.net.InetAddress;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedList;
import java.util.Queue;

import org.apache.mina.core.session.IoSession;
import org.pokenet.server.GameServer;
import org.pokenet.server.backend.entity.Bag;
import org.pokenet.server.backend.entity.PlayerChar;
import org.pokenet.server.backend.entity.PokemonBox;
import org.pokenet.server.backend.entity.PlayerChar.Language;
import org.pokenet.server.battle.DataService;
import org.pokenet.server.battle.Pokemon;
import org.pokenet.server.battle.PokemonSpecies;
import org.pokenet.server.battle.Pokemon.ExpTypes;
import org.pokenet.server.battle.mechanics.PokemonNature;
import org.pokenet.server.battle.mechanics.moves.MoveListEntry;
import org.pokenet.server.feature.TimeService;

/**
 * Handles logging players in
 * @author shadowkanji
 *
 */
public class LoginManager implements Runnable {
	private Queue<Object []> m_loginQueue;
	private Thread m_thread;
	private boolean m_isRunning;
	private MySqlManager m_database;
	
	private Queue<Object []> m_passChangeQueue;
	
	/**
	 * Default constructor. Requires a logout manager to be passed in so the server
	 * can check if player's data is not being saved as they are logging in.
	 * @param manager
	 */
	public LoginManager(LogoutManager manager) {
		m_database = new MySqlManager();
		m_loginQueue = new LinkedList<Object []>();
		m_passChangeQueue = new LinkedList<Object []>();
		m_thread = null;
	}
	
	/**
	 * Returns the ip address of a session
	 * @param s
	 * @return
	 */
	private String getIp(IoSession s) {
		if(s != null) {
			String ip = s.getRemoteAddress().toString();
			ip = ip.substring(1);
			ip = ip.substring(0, ip.indexOf(":"));
			return ip;
		} else {
			return "";
		}
	}
	
	/**
	 * Attempts to login a player. Upon success, it sends a packet to the player to inform them they are logged in.
	 * @param session
	 * @param l
	 * @param username
	 * @param password
	 */
	private void attemptLogin(IoSession session, char l, String username, String password, boolean force) {
		try {
			//Check if we haven't reach the player limit
			if(TcpProtocolHandler.getPlayerCount() >= GameServer.getMaxPlayers()) {
				session.write("l2");
				return;
			}
			//First connect to the database
			m_database = new MySqlManager();
			if(!m_database.connect(GameServer.getDatabaseHost(), GameServer.getDatabaseUsername(), GameServer.getDatabasePassword())) {
				session.write("l1");
				return;
			}
			//Select the database
			if(!m_database.selectDatabase(GameServer.getDatabaseName())) {
				session.write("l1");
				return;
			}
			//Now, check they are not banned
			ResultSet result = m_database.query("SELECT * FROM pn_bans WHERE ip='" + getIp(session) + "'");
			if(result != null && result.first()) {
				//This is player is banned, inform them
				session.write("l4");
				return;
			}
			//Then find the member's information
			result = m_database.query("SELECT * FROM pn_members WHERE username='" + MySqlManager.parseSQL(username) + "'");
			if(!result.first()){
				//Member doesn't exist, say user or pass wrong. We don't want someone to guess usernames. 
				session.write("le");
				return;
			}
			//Check if the password is correct
			if(result.getString("password").compareTo(password) == 0) {
				//Remove the player from the map to prevent duplicates
				GameServer.getServiceManager().getMovementService().removePlayer(username);
				long time = System.currentTimeMillis();
				//Now check if they are logged in anywhere else
				if(result.getString("lastLoginServer").equalsIgnoreCase(GameServer.getServerName())) {
					/*
					 * They are already logged in on this server.
					 * Attach the session to the existing player if they exist, if not, just log them in
					 */
					if(TcpProtocolHandler.containsPlayer(username)) {
						PlayerChar p = TcpProtocolHandler.getPlayer(username);
						p.getTcpSession().setAttribute("player", null);
						p.setLastLoginTime(time);
						p.getTcpSession().close(true);
						p.setTcpSession(session);
						p.setLanguage(Language.values()[Integer.parseInt(String.valueOf(l))]);
						m_database.query("UPDATE pn_members SET lastLoginServer='" + MySqlManager.parseSQL(GameServer.getServerName()) + "', lastLoginTime='" + time + "' WHERE username='" + MySqlManager.parseSQL(username) + "'");
						m_database.query("UPDATE pn_members SET lastLoginIP='" + getIp(session) + "' WHERE username='" + MySqlManager.parseSQL(username) + "'");
						session.setAttribute("player", p);
						this.initialiseClient(p, session);
					} else {
						session.write("l3");
						return;
					}
				} else if(result.getString("lastLoginServer").equalsIgnoreCase("null")) {
					/*
					 * They are not logged in elsewhere, log them in
					 */
					this.login(username, l, session, result);
				} else {
					/*
					 * They are logged in somewhere else.
					 * Check if the server is up, if it is, don't log them in. If not, log them in
					 */
					if(InetAddress.getByName(result.getString("lastLoginServer")).isReachable(5000)) {
						if(force) {
							this.login(username, l, session, result);
						} else {
							session.write("l3");
						}
						return;
					} else {
						//The server they were on went down and they are trying to login elsewhere
						this.login(username, l, session, result);
					}
				}
			} else {
				//Password is wrong, say so.
				session.write("le");
				return;
			}
			m_database.close();
		} catch (Exception e) {
			e.printStackTrace();
			session.write("lu");
			/*
			 * Something went wrong so make sure the player is registered as logged out
			 */
			m_database.query("UPDATE pn_members SET lastLoginServer='null' WHERE username='" + MySqlManager.parseSQL(username) + "'");
			m_database.close();
		}
	}
	
	/**
	 * Places a player in the login queue
	 * @param session
	 * @param username
	 * @param password
	 * @param forceLogin - true if player wants to force login
	 */
	public void queuePlayer(IoSession session, String username, String password, boolean forceLogin) {
		if(m_thread == null || !m_thread.isAlive()) {
			start();
		}
		m_loginQueue.offer(new Object[] {session, username, password, String.valueOf(forceLogin)});
	}
	
	/**
	 * Places a player in the queue to update their password
	 * @param session
	 * @param username
	 * @param newPassword
	 * @param oldPassword
	 */
	public void queuePasswordChange(IoSession session, String username, String newPassword, String oldPassword) {
		if(m_thread == null || !m_thread.isAlive()) {
			start();
		}
		m_passChangeQueue.offer(new Object[] {session, username, newPassword, oldPassword});
	}

	/**
	 * Called by Thread.start()
	 */
	public void run() {
		Object [] o;
		IoSession session;
		String username;
		String password;
		String newPassword;
		char l;
		while(m_isRunning) {
			synchronized(m_loginQueue) {
				try {
					if(m_loginQueue.peek() != null) {
						o = m_loginQueue.poll();
						session = (IoSession) o[0];
						l = ((String) o[1]).charAt(0);
						username = ((String) o[1]).substring(1);
						password = (String) o[2];
						boolean force = Boolean.valueOf((String) o[3]);
						this.attemptLogin(session, l, username, password, force);
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			try {
				Thread.sleep(500);
			} catch (Exception e) {}
			
			synchronized(m_passChangeQueue) {
				try {
					if(m_passChangeQueue.peek() != null) {
						o = m_passChangeQueue.poll();
						session = (IoSession) o[0];
						username = (String) o[1];
						newPassword = (String) o[2];
						password = (String) o[3];
						this.changePass(username, newPassword, password, session);
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			try {
				Thread.sleep(500);
			} catch (Exception e) {}
		}
		m_thread = null;
	}
	
	/**
	 * Starts the login manager
	 */
	public void start() {
		if(m_thread == null || !m_thread.isAlive()) {
			m_thread = new Thread(this);
			m_isRunning = true;
			m_thread.start();
		}
	}
	
	/**
	 * Stops the login manager
	 */
	public void stop() {
		m_isRunning = false;
	}
	
	/**
	 * Changes the password of a player
	 * @param username
	 * @param newPassword
	 * @param oldPassword
	 * @param session
	 */
	private void changePass(String username, String newPassword, String oldPassword, IoSession session) {
		m_database = new MySqlManager();
	
		if(m_database.connect(GameServer.getDatabaseHost(), GameServer.getDatabaseUsername(), GameServer.getDatabasePassword())) {
			if(m_database.selectDatabase(GameServer.getDatabaseName())) {
				ResultSet result = m_database.query("SELECT * FROM pn_members WHERE username='" + MySqlManager.parseSQL(username) + "'");
				try {
					if(result.first()){
						// if we got a result, compare their old password to the one we have stored for them
						if(result.getString("password").compareTo(oldPassword) == 0) {
							// old password matches the one on file, therefore they got their old password correct, so it can be changed to their new one
							m_database.query("UPDATE pn_members SET password='" + MySqlManager.parseSQL(newPassword) + "' WHERE username='" + MySqlManager.parseSQL(username) + "'");
							// tell them their password was changed successfully
							session.write("ps");
							return;
						}
					}
				} catch (SQLException e) {
					e.printStackTrace();
				}
				m_database.close();
			}
		}
		// tell them we failed to change their password
		session.write("pe");
	}
	
	/**
	 * Logs in a player
	 * @param username
	 * @param language
	 * @param session
	 * @param result
	 */
	private void login(String username, char language, IoSession session, ResultSet result) {
		//They are not logged in elsewhere, set the current login to the current server
		long time = System.currentTimeMillis();
		/*
		 * Attempt to log the player in
		 */
		PlayerChar p = getPlayerObject(result);
		p.setLastLoginTime(time);
		p.setTcpSession(session);
		p.setLanguage(Language.values()[Integer.parseInt(String.valueOf(language))]);
		p.generateUdpCode();
		/*
		 * Update the database with login information
		 */
		m_database.query("UPDATE pn_members SET lastLoginServer='" + MySqlManager.parseSQL(GameServer.getServerName()) + "', lastLoginTime='" + time + "' WHERE username='" + MySqlManager.parseSQL(username) + "'");
		m_database.query("UPDATE pn_members SET lastLoginIP='" + getIp(session) + "' WHERE username='" + MySqlManager.parseSQL(username) + "'");
		session.setAttribute("player", p);
		/*
		 * Send success packet to player, set their map and add them to a movement service
		 */
		this.initialiseClient(p, session);
		/*
		 * Add them to the list of players
		 */
		TcpProtocolHandler.addPlayer(p);
		UdpProtocolHandler.addPlayer(p);
		GameServer.getInstance().updatePlayerCount();
		System.out.println("INFO: " + username + " logged in.");
	}
	
	/**
	 * Sends initial information to the client
	 * @param p
	 * @param session
	 */
	private void initialiseClient(PlayerChar p, IoSession session) {
		session.write("ls" + p.getId() + "," + p.getUdpCode() + "," + TimeService.getTime());
		//Add them to the map
		p.setMap(GameServer.getServiceManager().getMovementService().getMapMatrix().getMapByGamePosition(p.getMapX(), p.getMapY()), null);
		//Add them to a movement service
		GameServer.getServiceManager().getMovementService().getMovementManager().addPlayer(p);
		//Send their Pokemon information to them
		p.updateClientParty();
		//Send bag to them
		p.updateClientBag();
		//Send money
		p.updateClientMoney();
		//Send their friend list to them
//		p.updateClientFriends();
		//Send badges
		p.updateClientBadges();
		p.initializeClientSkills();
	}

	/**
	 * Returns a playerchar object from a resultset of player data
	 * @param data
	 * @return
	 */
	private PlayerChar getPlayerObject(ResultSet result) {
		try {
			PlayerChar p = new PlayerChar();
			Pokemon [] party = new Pokemon[6];
			
			p.setName(result.getString("username"));
			p.setVisible(true);
			//Set co-ordinates
			p.setX(result.getInt("x"));
			p.setY(result.getInt("y"));
			p.setMapX(result.getInt("mapX"));
			p.setMapY(result.getInt("mapY"));
			p.setId(result.getInt("id"));
			p.setAdminLevel(result.getInt("adminLevel"));
			p.setMuted(result.getBoolean("muted"));
			p.setLastHeal(result.getInt("healX"), result.getInt("healY"), result.getInt("healMapX"), result.getInt("healMapY"));
			p.setSurfing(Boolean.parseBoolean(result.getString("isSurfing")));
			//Set money and skills
			p.setSprite(result.getInt("sprite"));
			p.setMoney(result.getInt("money"));
			p.setHerbalismExp(result.getInt("skHerb"));
			p.setCraftingExp(result.getInt("skCraft"));
			p.setFishingExp(result.getInt("skFish"));
			p.setTrainingExp(result.getInt("skTrain"));
			p.setCoordinatingExp(result.getInt("skCoord"));
			p.setBreedingExp(result.getInt("skBreed"));
			//Retrieve refences to all Pokemon
			int pokesId = result.getInt("pokemons");
			ResultSet pokemons = m_database.query("SELECT * FROM pn_mypokes WHERE id='" + pokesId + "'");
			pokemons.first();
			p.setDatabasePokemon(pokemons);
			//Attach party
			ResultSet partyInfo = m_database.query("SELECT * FROM pn_party WHERE id='" + pokemons.getInt("party") + "'");
			partyInfo.first();
			for(int i = 0; i < 6; i++) {
				party[i] = partyInfo.getInt("pokemon" + i) != -1 ? 
						getPokemonObject(m_database.query("SELECT * FROM pn_pokemon WHERE id='" + partyInfo.getInt("pokemon" + i) + "'"))
						: null;
			}
			p.setParty(party);
			//Attach boxes
			PokemonBox[] boxes = new PokemonBox[9];
			ResultSet boxInfo;
			for(int i = 0; i < 9; i++) {
				/*
				 * -1 is stored in the database if no box exists
				 */
				if(pokemons.getInt("box" + i) != -1) {
					boxInfo = m_database.query("SELECT * FROM pn_box WHERE id='" + pokemons.getInt("box" + i) + "'");
					boxInfo.first();
					boxes[i] = new PokemonBox();
					boxes[i].setPokemon(new Pokemon[30]);
					boxes[i].setDatabaseId(boxInfo.getInt("id"));
					for(int j = 0; j < 30; j++) {
						/*
						 * -1 stored in the database if no pokemon exists
						 */
						if(boxInfo.getInt("pokemon" + j) > 0) {
							boxes[i].setPokemon(j, getPokemonObject(m_database.query("SELECT * FROM pn_pokemon WHERE id='" + boxInfo.getInt("pokemon" + j) + "'")));
						} else {
							boxes[i].setPokemon(j, null);
						}
					}
				}
			}
			p.setBoxes(boxes);
			//Attach bag
			p.setBag(getBagObject(m_database.query("SELECT * FROM pn_bag WHERE member='" + result.getInt("id") + "'"),p.getId()));

			//Attach badges
			p.generateBadges(result.getString("badges"));
			return p;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}
	
	/**
	 * Returns a Pokemon object based on a set of data
	 * @param data
	 * @return
	 */
	private Pokemon getPokemonObject(ResultSet data) {
		if(data != null) {
			try {
				data.first();
				/*
				 * First generate the Pokemons moves
				 */
				MoveListEntry [] moves = new MoveListEntry[4];
				moves[0] = (data.getString("move0") != null && !data.getString("move0").equalsIgnoreCase("null") ?
						DataService.getMovesList().getMove(data.getString("move0")) :
							null);
				moves[1] = (data.getString("move1") != null && !data.getString("move1").equalsIgnoreCase("null") ?
						DataService.getMovesList().getMove(data.getString("move1")) :
							null);
				moves[2] = (data.getString("move2") != null && !data.getString("move2").equalsIgnoreCase("null") ?
						DataService.getMovesList().getMove(data.getString("move2")) :
							null);
				moves[3] = (data.getString("move3") != null && !data.getString("move3").equalsIgnoreCase("null") ?
						DataService.getMovesList().getMove(data.getString("move3")) :
							null);
				/*
				 * Create the new Pokemon
				 */
				Pokemon p = new Pokemon(
						DataService.getBattleMechanics(),
						PokemonSpecies.getDefaultData().getSpecies(
								PokemonSpecies.getDefaultData().getPokemonByName(data.getString("speciesName")))
						,
						PokemonNature.getNatureByName(data.getString("nature")),
						data.getString("abilityName"),
						data.getString("itemName"),
						data.getInt("gender"),
						data.getInt("level"),
						new int[] { 
							data.getInt("ivHP"),
							data.getInt("ivATK"),
							data.getInt("ivDEF"),
							data.getInt("ivSPD"),
							data.getInt("ivSPATK"),
							data.getInt("ivSPDEF")},
						new int[] { 
							data.getInt("evHP"),
							data.getInt("evATK"),
							data.getInt("evDEF"),
							data.getInt("evSPD"),
							data.getInt("evSPATK"),
							data.getInt("evSPDEF")},
						moves,
						new int[] {
							data.getInt("ppUp0"),
							data.getInt("ppUp1"),
							data.getInt("ppUp2"),
							data.getInt("ppUp3")
						});
				p.reinitialise();
				/*
				 * Set exp, nickname, isShiny and exp gain type
				 */
				p.setBaseExp(data.getInt("baseExp"));
				p.setExp(Double.parseDouble(data.getString("exp")));
				p.setName(data.getString("name"));
				p.setHappiness(data.getInt("happiness"));
				p.setShiny(Boolean.parseBoolean(data.getString("isShiny")));
				p.setExpType(ExpTypes.valueOf(data.getString("expType")));
				p.setOriginalTrainer(data.getString("originalTrainerName"));
				p.setDatabaseID(data.getInt("id"));
				p.setDateCaught(data.getString("date"));
				p.setIsFainted(Boolean.parseBoolean(data.getString("isFainted")));
				/*
				 * Contest stats (beauty, cute, etc.)
				 */
				String [] cstats = data.getString("contestStats").split(",");
				p.setContestStat(0, Integer.parseInt(cstats[0]));
				p.setContestStat(1, Integer.parseInt(cstats[1]));
				p.setContestStat(2, Integer.parseInt(cstats[2]));
				p.setContestStat(3, Integer.parseInt(cstats[3]));
				p.setContestStat(4, Integer.parseInt(cstats[4]));
				/*
				 * Sets the stats
				 */
				p.calculateStats(true);
				p.setHealth(data.getInt("hp"));
				p.setRawStat(1, data.getInt("atk"));
				p.setRawStat(2, data.getInt("def"));
				p.setRawStat(3, data.getInt("speed"));
				p.setRawStat(4, data.getInt("spATK"));
				p.setRawStat(5, data.getInt("spDEF"));
				/*
				 * Sets the pp information
				 */
				p.setPp(0, data.getInt("pp0"));
				p.setPp(1, data.getInt("pp1"));
				p.setPp(2, data.getInt("pp2"));
				p.setPp(3, data.getInt("pp3"));
				/*p.setMaxPP(0, data.getInt("maxpp0"));
				p.setMaxPP(0, data.getInt("maxpp1"));
				p.setMaxPP(0, data.getInt("maxpp2"));
				p.setMaxPP(0, data.getInt("maxpp3"));*/
				p.setPpUp(0, data.getInt("ppUp0"));
				p.setPpUp(0, data.getInt("ppUp1"));
				p.setPpUp(0, data.getInt("ppUp2"));
				p.setPpUp(0, data.getInt("ppUp3"));
				return p;
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return null;
	}
	
	/**
	 * Returns a bag object
	 * @param data
	 * @return
	 */
	private Bag getBagObject(ResultSet data, int memberid) {
		try {
			Bag b = new Bag(memberid);
			while(data.next()){
				b.addItem(data.getInt("item"), data.getInt("quantity"));
			}
			return b;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
		
	}
}
