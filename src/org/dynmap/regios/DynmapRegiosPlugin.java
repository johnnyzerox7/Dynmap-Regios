package org.dynmap.regios;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerSet;

import couk.Adamki11s.Regios.API.RegiosAPI;
import couk.Adamki11s.Regios.Main.Regios;
import couk.Adamki11s.Regios.Regions.Region;

public class DynmapRegiosPlugin extends JavaPlugin {
	private static final Logger log = Logger.getLogger("Minecraft");
	private static final String LOG_PREFIX = "[Dynmap-Regios] ";
	private static final String DEF_INFOWINDOW = "<div class=\"infowindow\"><span style=\"font-size:120%;\">%regionname%</span><br /> Owner: <span style=\"font-weight:bold;\">%playerowner%</span><br /> Protected: <span style=\"font-weight:bold;\">%protected%</span><br /> Protected-BB: <span style=\"font-weight:bold;\">%protectedbb%</span><br /> Protected-BP: <span style=\"font-weight:bold;\">%protectedbp%</span><br /> Prevent-Entry: <span style=\"font-weight:bold;\">%preventry%</span><br /> Prevent-Exit: <span style=\"font-weight:bold;\">%prevexit%</span><br /> PVP-Enabled: <span style=\"font-weight:bold;\">%pvp%</span></div>";
	Plugin dynmap;
	DynmapAPI api;
	MarkerAPI markerapi;
	Regios reg;
	RegiosAPI regapi = new RegiosAPI();

	FileConfiguration cfg;
	MarkerSet set;
	long updperiod;
	boolean use3d;
	String infowindow;
	AreaStyle defstyle;
	Map<String, AreaStyle> cusstyle;
	Map<String, AreaStyle> cuswildstyle;
	Set<String> visible;
	Set<String> hidden;
	boolean stop; 
	int maxdepth;

	private static class AreaStyle {
		String strokecolor;
		double strokeopacity;
		int strokeweight;
		String fillcolor;
		double fillopacity;
		String label;

		AreaStyle(FileConfiguration cfg, String path, AreaStyle def) {
			strokecolor = cfg.getString(path+".strokeColor", def.strokecolor);
			strokeopacity = cfg.getDouble(path+".strokeOpacity", def.strokeopacity);
			strokeweight = cfg.getInt(path+".strokeWeight", def.strokeweight);
			fillcolor = cfg.getString(path+".fillColor", def.fillcolor);
			fillopacity = cfg.getDouble(path+".fillOpacity", def.fillopacity);
			label = cfg.getString(path+".label", null);
		}

		AreaStyle(FileConfiguration cfg, String path) {
			strokecolor = cfg.getString(path+".strokeColor", "#FF0000");
			strokeopacity = cfg.getDouble(path+".strokeOpacity", 0.8);
			strokeweight = cfg.getInt(path+".strokeWeight", 3);
			fillcolor = cfg.getString(path+".fillColor", "#FF0000");
			fillopacity = cfg.getDouble(path+".fillOpacity", 0.35);
		}
	}

	public static void info(String msg) {
		log.log(Level.INFO, LOG_PREFIX + msg);
	}
	public static void severe(String msg) {
		log.log(Level.SEVERE, LOG_PREFIX + msg);
	}

	private class RegiosUpdate implements Runnable {
		public void run() {
			if(!stop)
				updateRegions();
		}
	}

	private Map<String, AreaMarker> resareas = new HashMap<String, AreaMarker>();

	private String formatInfoWindow(Region region, AreaMarker m) {
		String v = "<div class=\"regioninfo\">"+infowindow+"</div>";
		v = v.replace("%regionname%", m.getLabel());
		v = v.replace("%playerowner%", region.getOwner());
		v = v.replace("%protected%", String.valueOf(region.isProtected()));
		v = v.replace("%protectedbb%", String.valueOf(region.is_protectionBreak()));
		v = v.replace("%protectedbp%", String.valueOf(region.is_protectionPlace()));
		v = v.replace("%preventry%", String.valueOf(region.isPreventEntry()));
		v = v.replace("%prevexit%", String.valueOf(region.isPreventExit()));
		v = v.replace("%pvp%", String.valueOf(region.isPvp()));
		return v;
	}

	private boolean isVisible(String id, String worldname) {
		if((visible != null) && (visible.size() > 0)) {
			if((visible.contains(id) == false) && (visible.contains("world:" + worldname) == false) &&
					(visible.contains(worldname + "/" + id) == false)) {
				return false;
			}
		}
		if((hidden != null) && (hidden.size() > 0)) {
			if(hidden.contains(id) || hidden.contains("world:" + worldname) || hidden.contains(worldname + "/" + id))
				return false;
		}
		return true;
	}

	private void addStyle(String resid, String worldid, AreaMarker m, Region region) {
		AreaStyle as = cusstyle.get(worldid + "/" + resid);
		if(as == null) {
			as = cusstyle.get(resid);
		}
		if(as == null) {    /* Check for wildcard style matches */
			for(String wc : cuswildstyle.keySet()) {
				String[] tok = wc.split("\\|");
				if((tok.length == 1) && resid.startsWith(tok[0]))
					as = cuswildstyle.get(wc);
				else if((tok.length >= 2) && resid.startsWith(tok[0]) && resid.endsWith(tok[1]))
					as = cuswildstyle.get(wc);
			}
		}
		if(as == null)
			as = defstyle;

		int sc = 0xFF0000;
		int fc = 0xFF0000;
		try {
			sc = Integer.parseInt(as.strokecolor.substring(1), 16);
			fc = Integer.parseInt(as.fillcolor.substring(1), 16);
		} catch (NumberFormatException nfx) {
		}
		m.setLineStyle(as.strokeweight, as.strokeopacity, sc);
		m.setFillStyle(as.fillopacity, fc);
		if(as.label != null) {
			m.setLabel(as.label);
		}
	}

	/* Handle specific region */
	private void handleRegion(World world, Region pr, Map<String, AreaMarker> newmap) {
		String name = pr.getName();
		double[] x = null;
		double[] z = null;

		/* Handle areas */
		if(isVisible(pr.getName(), world.getName())) {
			String id = pr.getName();
			Location l0 = pr.getL1();
			Location l1 = pr.getL2();

			/* Make outline */
			x = new double[4];
			z = new double[4];
			x[0] = l0.getX(); z[0] = l0.getZ();
			x[1] = l0.getX(); z[1] = l1.getZ()+1.0;
			x[2] = l1.getX() + 1.0; z[2] = l1.getZ()+1.0;
			x[3] = l1.getX() + 1.0; z[3] = l0.getZ();

			String markerid = world.getName() + "_" + id;
			AreaMarker m = resareas.remove(markerid); /* Existing area? */
			if(m == null) {
				m = set.createAreaMarker(markerid, name, false, world.getName(), x, z, false);
				if(m == null)
					return;
			}
			else {
				m.setCornerLocations(x, z); /* Replace corner locations */
				m.setLabel(name);   /* Update label */
			}
			if(use3d) { /* If 3D? */
				m.setRangeY(l1.getY()+1.0, l0.getY());
			}            
			/* Set line and fill properties */
			addStyle(id, world.getName(), m, pr);

			/* Build popup */
			String desc = formatInfoWindow(pr, m);

			m.setDescription(desc); /* Set popup */

			/* Add to map */
			newmap.put(markerid, m);
		}
	}

	/* Update regios region information */
	private void updateRegions() {
		Map<String,AreaMarker> newmap = new HashMap<String,AreaMarker>(); /* Build new map */

		/* Loop through worlds */
		for(World w : getServer().getWorlds()) {
			ArrayList<Region> regions = regapi.getRegions(w);  /* Get all the regions */
			for(Region pr : regions) {
//				if(pr.getWorld().getName().equalsIgnoreCase(w.getName())) {
//					handleRegion(w, pr, newmap);
//				}
				handleRegion(w, pr, newmap);
			}
		}
		/* Now, review old map - anything left is gone */
		for(AreaMarker oldm : resareas.values()) {
			oldm.deleteMarker();
		}
		/* And replace with new map */
		resareas = newmap;

		getServer().getScheduler().scheduleSyncDelayedTask(this, new RegiosUpdate(), updperiod);

	}

	private class OurServerListener implements Listener {
		@SuppressWarnings("unused")
		@EventHandler(priority=EventPriority.MONITOR)
		public void onPluginEnable(PluginEnableEvent event) {
			Plugin p = event.getPlugin();
			String name = p.getDescription().getName();
			if(name.equals("dynmap") || name.equals("Regios")) {
				if(dynmap.isEnabled() && reg.isEnabled())
					activate();
			}
		}
	}

	public void onEnable() {
		info("initializing");
		PluginManager pm = getServer().getPluginManager();
		/* Get dynmap */
		dynmap = pm.getPlugin("dynmap");
		if(dynmap == null) {
			severe("Cannot find dynmap!");
			return;
		}
		api = (DynmapAPI)dynmap; /* Get API */
		/* Get Regios */
		Plugin p = pm.getPlugin("Regios");
		if(p == null) {
			severe("Cannot find Regios!");
			return;
		}
		reg = (Regios)p;

		getServer().getPluginManager().registerEvents(new OurServerListener(), this);        
		/* If both enabled, activate */
		if(dynmap.isEnabled() && reg.isEnabled())
			activate();
	}

	private void activate() {
		/* Now, get markers API */
		markerapi = api.getMarkerAPI();
		if(markerapi == null) {
			severe("Error loading dynmap marker API!");
			return;
		}
		/* Load configuration */
		FileConfiguration cfg = getConfig();
		cfg.options().copyDefaults(true);   /* Load defaults, if needed */
		this.saveConfig();  /* Save updates, if needed */

		/* Now, add marker set for mobs (make it transient) */
		set = markerapi.getMarkerSet("regios.markerset");
		if(set == null)
			set = markerapi.createMarkerSet("regios.markerset", cfg.getString("layer.name", "Regios"), null, false);
		else
			set.setMarkerSetLabel(cfg.getString("layer.name", "Regios"));
		if(set == null) {
			severe("Error creating marker set");
			return;
		}
		int minzoom = cfg.getInt("layer.minzoom", 0);
		if(minzoom > 0)
			set.setMinZoom(minzoom);
		set.setLayerPriority(cfg.getInt("layer.layerprio", 10));
		set.setHideByDefault(cfg.getBoolean("layer.hidebydefault", false));
		use3d = cfg.getBoolean("use3dregions", false);
		infowindow = cfg.getString("infowindow", DEF_INFOWINDOW);
		maxdepth = cfg.getInt("maxdepth", 16);

		/* Get style information */
		defstyle = new AreaStyle(cfg, "regionstyle");
		cusstyle = new HashMap<String, AreaStyle>();
		cuswildstyle = new HashMap<String, AreaStyle>();
		ConfigurationSection sect = cfg.getConfigurationSection("custstyle");
		if(sect != null) {
			Set<String> ids = sect.getKeys(false);

			for(String id : ids) {
				if(id.indexOf('|') >= 0)
					cuswildstyle.put(id, new AreaStyle(cfg, "custstyle." + id, defstyle));
				else
					cusstyle.put(id, new AreaStyle(cfg, "custstyle." + id, defstyle));
			}
		}
		List<String> vis = cfg.getStringList("visibleregions");
		if(vis != null) {
			visible = new HashSet<String>(vis);
		}
		List<String> hid = cfg.getStringList("hiddenregions");
		if(hid != null) {
			hidden = new HashSet<String>(hid);
		}

		/* Set up update job - based on period */
		int per = cfg.getInt("update.period", 300);
		if(per < 15) per = 15;
		updperiod = (long)(per*20);
		stop = false;

		getServer().getScheduler().scheduleSyncDelayedTask(this, new RegiosUpdate(), 40);   /* First time is 2 seconds */

		info("version " + this.getDescription().getVersion() + " is activated");
	}

	public void onDisable() {
		if(set != null) {
			set.deleteMarkerSet();
			set = null;
		}
		resareas.clear();
		stop = true;
	}

}
