/*
 * 
 * Copyright (c) 2021 Ghent University. 
 * All rights reserved.
 *
 * Author: Yun Zhou, Ghent University.
 *
 * This file is part of RapidWright. 
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package com.xilinx.rapidwright.rwroute;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.ClockRegion;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.placer.blockplacer.Point;
import com.xilinx.rapidwright.placer.blockplacer.SmallestEnclosingCircle;
import com.xilinx.rapidwright.router.RouteNode;
import com.xilinx.rapidwright.router.RouteThruHelper;
import com.xilinx.rapidwright.router.UltraScaleClockRouting;
import com.xilinx.rapidwright.timing.CERouteTiming;
import com.xilinx.rapidwright.util.Pair;

/**
 * A collection of methods for routing global signals, i.e. GLOBAL_CLOCK, VCC and GND.
 * Adapted from RapidWright APIs.
 */
public class GlobalSignalRouting {
	private static Design design;
	private static RouteThruHelper routeThruHelper;
	
	static boolean clkDebug = false;
	static boolean debugPrintClkPIPs = false;
	
	static Map<String, List<String>> crRoutes;
	static Map<Pair<String, String>, List<Short>> bufceRowTaps;
	static Device device;
	
	static Map<String, List<String>> dstINTtileRoutes;
	static Map<String, Short> dstINTtileRoutey;
	static CERouteTiming ceRouteTiming;
	
	public static Map<Node, RoutingNode> createdRoutingNodes;
	static {
		createdRoutingNodes = new HashMap<>();
	}
	
	public static void setRouteMap(Map<String, List<String>> routeMap, Map<Pair<String, String>, List<Short>> delays) {
		crRoutes = new HashMap<>();
		crRoutes = routeMap;
		bufceRowTaps = new HashMap<>();
		bufceRowTaps = delays;
	}
	
	public static void setCERouteTiming(CERouteTiming ceTiming) {
		ceRouteTiming = ceTiming;
		if(ceRouteTiming == null) {
			System.err.println("CERouteTiming NOT EXIST");
			return;
		}
		dstINTtileRoutes = ceRouteTiming.getDstINTtileRoute();
	}
	
	public static void setDesignRoutethruHelper(Design design, RouteThruHelper routeThruHelper) {
		GlobalSignalRouting.design = design;
		GlobalSignalRouting.routeThruHelper = routeThruHelper;
	}
	
	public static void setDebug() {
		clkDebug = true;
	}
	public static void setPrintCLKPIPs() {
		debugPrintClkPIPs = true;
	}
	
	/**
	 * Routes a clk enable net with input data.
	 * @param clockEnable The net to be routed.
	 * @param device The design device.
	 */
	public static void clkEnableRoute(Net clockEnable, Device device) {
		Map<String, List<Node>> dstINTtilePaths = getListOfNodesFromRoutes(device, dstINTtileRoutes);
		// Not import path after HDSTR
		Set<PIP> ceNetPIPs = new HashSet<>();
		Map<String, RouteNode> horDistributionLines = new HashMap<>();
		
		for(String intTile : dstINTtilePaths.keySet()) {
			List<Node> nodes = dstINTtilePaths.get(intTile);
			Collections.reverse(nodes); // HDISTR to CLK_OUT
			Node hDistr = nodes.get(0);
			RouteNode hdistr = new RouteNode(hDistr.getTile(), hDistr.getWire());
			
			ceNetPIPs.addAll(RouterHelper.getPIPsFromListOfReversedNodes(nodes));
			
			horDistributionLines.put(getDominateClockRegion(hDistr), hdistr);
		}
		clockEnable.setPIPs(ceNetPIPs);
		
		Map<RouteNode, ArrayList<SitePinInst>> lcbMappings = getLCBPinMappings(clockEnable);		
		
		UltraScaleClockRouting.routeToLCBs(clockEnable, getStartingPoint(horDistributionLines, device), lcbMappings.keySet());	
		if(debugPrintClkPIPs) {
			System.out.println("ROUTE DISTR TO LCBs: ");
			printCLKPIPs(clockEnable);
			System.out.println();
		}
		
		// route LCBs to sink pins
		UltraScaleClockRouting.routeLCBsToSinks(clockEnable, lcbMappings);	
		if(debugPrintClkPIPs) {
			System.out.println("ROUTE LCB TO SINKs: ");
			printCLKPIPs(clockEnable);
			System.out.println();
		} 
		
		Set<PIP> clkPIPsWithoutDuplication = new HashSet<>();
		clkPIPsWithoutDuplication.addAll(clockEnable.getPIPs());
		clockEnable.setPIPs(clkPIPsWithoutDuplication);
		if(debugPrintClkPIPs) {
			System.out.println("FINAL CLK PIPs: ");
			printCLKPIPs(clockEnable);
			System.out.println();
		}	
	}
	
	private static Map<ClockRegion, Set<RouteNode>> getStartingPoint(Map<String, RouteNode> crDistLines, Device dev) {
		Map<ClockRegion, Set<RouteNode>> startingPoints = new HashMap<>();
		for(String crName : crDistLines.keySet()) {
			ClockRegion cr = dev.getClockRegion(crName);
			Set<RouteNode> routeNodes = startingPoints.get(cr);
			if(routeNodes == null){
				routeNodes = new HashSet<>();
				startingPoints.put(cr, routeNodes);
			}
			routeNodes.add(crDistLines.get(crName));
		}
		return startingPoints;
	}
	
	private static String getDominateClockRegion(Node node){
		/** 
		 * This is needed because a HDISTR for clock region X3Y2 can have a base tile in clock region X2Y2, 
		 * seen from optical-flow design checkpoint.
		 */
		Map<String, Integer> crCounts = new HashMap<>();
		for(Wire w : node.getAllWiresInNode()) {
			ClockRegion cr = w.getTile().getClockRegion();
			if(cr == null) {
				continue;
			}
			Integer count = crCounts.get(cr.getName());
			if(count == null) {
				count = 1;
			}else {
				count++;
			}
			crCounts.put(cr.getName(), count);
		}
		
		String dominate = null;
		int max = 0;
		for(String cr : crCounts.keySet()) {
			if(crCounts.get(cr) > max) {
				max = crCounts.get(cr);
				dominate = cr;
			}
		}
		
		return dominate;
	}
	
	/**
	 * Route a clock net with clock skew data, routes and tap delays.
	 * @param clk The clock net to be routed.
	 * @param device The design device.
	 */
	public static void clkRouteWithClkSkewRouteDelays(Net clk, Device device) {
		Map<String, List<Node>> crPaths = getListOfNodesFromRoutes(device, crRoutes);
		Node centroidNode = null;
		for(String cr : crPaths.keySet()) {
			centroidNode = crPaths.get(cr).get(0);
			break;
		}
		if(debugPrintClkPIPs) System.out.println("CENTROID NODE: \n " + centroidNode);
		
		/** 1. route BUFG to nearest routing track */
		RouteNode startRoutingLine = UltraScaleClockRouting.routeBUFGToNearestRoutingTrack(clk);
		if(debugPrintClkPIPs) {
			System.out.println("ROUTE TO ROUTING TRACK: ");
			System.out.println( "  " + startRoutingLine);
			printCLKPIPs(clk);
			System.out.println();
		}
		
		/** 2. route from start routing line to the given centroid node */
		RouteNode centroidRouteNode = UltraScaleClockRouting.routeToGivenCentroidNode(clk, startRoutingLine, centroidNode);	
		/**
		 *  For the gnl design with clk, vivado needs to use another BUFGCE to route from startRoutingLine to centroidNode
		 *  Yet we could not find the path to centroid due to the searching condition assigned
		 */
		if(debugPrintClkPIPs) {
			System.out.println("PATH TO CENTROID FOUND: \n  " + centroidRouteNode);
			System.out.println("ROUTE TO CENTROID: ");
			printCLKPIPs(clk);
			System.out.println();
		}
		
		/** 3.a. get SitePinInst - LCB mapping */
		Map<RouteNode, ArrayList<SitePinInst>> lcbMappings = getLCBPinMappings(clk);
		
		/** 3.b. copy given paths from centroid to horizontal distribution lines */
		Map<String, RouteNode> horDistributionLines = routeCentroidToHorDistributionLines(clk, crPaths);	
		if(debugPrintClkPIPs) {
			System.out.println("HORIZONTAL DISTRIBUTION LINEs:");
			for(String cr : horDistributionLines.keySet()) {
				System.out.println(cr + "  " + horDistributionLines.get(cr));
			}
		}
		
		/** 3.c. route to LCBs */
		UltraScaleClockRouting.routeToLCBs(clk, getStartingPoint(horDistributionLines, device), lcbMappings.keySet());	
		if(debugPrintClkPIPs) {
			System.out.println("ROUTE DISTR TO LCBs: ");
			printCLKPIPs(clk);
			System.out.println();
		}
		
		/** 4. route LCBs to sink pins */
		UltraScaleClockRouting.routeLCBsToSinks(clk, lcbMappings);	
		if(debugPrintClkPIPs) {
			System.out.println("ROUTE LCB TO SINKs: ");
			printCLKPIPs(clk);
			System.out.println();
		} 
		
		Set<PIP> clkPIPsWithoutDuplication = new HashSet<>();
		clkPIPsWithoutDuplication.addAll(clk.getPIPs());
		clk.setPIPs(clkPIPsWithoutDuplication);
		if(debugPrintClkPIPs) {
			System.out.println("FINAL CLK PIPs: ");
			printCLKPIPs(clk);
			System.out.println();
		}
		
		/** 5. set delay */
		setBUFCERowLeafTap(clk, device);
	}
	
	/**
	 * Sets BUGCE row and leaf tap levels of a routed clock net.
	 * @param clk The target clock net.
	 * @param device The design device.
	 */
	private static void setBUFCERowLeafTap(Net clk, Device device) {
		if(bufceRowTaps.isEmpty()) return;		
		if(debugPrintClkPIPs) System.out.println(bufceRowTaps);		
		List<Site> sites = new ArrayList<>();
		for(Pair<String, String> crSite: bufceRowTaps.keySet()) {
			Site site = device.getSite(crSite.getSecond());
			/** e.g. row_tap  leaf_tap	src_@0.3  src_@0.6  src_@0.9   dst_@0.3  dst_@0.6  dst_@0.9 */
			clk.setBufferDelay(site, bufceRowTaps.get(crSite).get(0));
			sites.add(site);
		}			
		
		for(PIP p:clk.getPIPs()) {
			if(p.getStartNode().toString().contains("_CLK_IN") && p.getEndNode().toString().contains("_CLK_LEAF")) {
				Site s = p.getStartNode().getSitePin().getSite();
				String cr = s.getTile().getClockRegion().getName();		
				List<Short> taps = null;
				for(Pair<String, String> crRowbufSite : bufceRowTaps.keySet()) {
					if(crRowbufSite.getFirst().equals(cr)) {
						taps = bufceRowTaps.get(crRowbufSite);
					}
				}		
				if(taps != null) {
					clk.setBufferDelay(s, taps.get(1));
				}			
				if(!sites.contains(s)) sites.add(s);
			}
		}	
		if(debugPrintClkPIPs) {
			for(Site site : sites) {
				System.out.println(site + ", cr " + site.getTile().getClockRegion() + ", delay = " + clk.getBufferDelay(site));
			}
		}
	}
	
	private static Map<String, RouteNode> routeCentroidToHorDistributionLines(Net clk, Map<String, List<Node>> crPaths){
		Map<String, RouteNode> crHorizontalDistributionLines = new HashMap<>();
		for(String cr : crPaths.keySet()) {
			List<Node> path = crPaths.get(cr);
			Collections.reverse(path);
			clk.getPIPs().addAll(RouterHelper.getPIPsFromListOfReversedNodes(path));
			Node hdistr = path.get(0);
			if(hdistr.getIntentCode() != IntentCode.NODE_GLOBAL_HDISTR) {
				System.err.println("ERROR: The last node of the path is not HDISTR");
				continue;
			}
			RouteNode hDistr = new RouteNode(hdistr.getTile(), hdistr.getWire());
			crHorizontalDistributionLines.put(cr, hDistr);	
		}
		return crHorizontalDistributionLines;
	}
	
	/**
	 * Gets a list of nodes for each clock region based on the names of nodes.
	 * @param device
	 * @param dstRoutes
	 * @return
	 */
	private static Map<String, List<Node>> getListOfNodesFromRoutes(Device device, Map<String, List<String>> dstRoutes){
		Map<String, List<Node>> dstPaths = new HashMap<>();
		for(String dst : dstRoutes.keySet()) {
			List<Node> pathNodes = new ArrayList<>();
			for(String nodeName : dstRoutes.get(dst)) {
				Node node = Node.getNode(nodeName, device);
				if(node != null) {
					pathNodes.add(node);
				}else {
					System.err.println("NULL NODE FOUND " + nodeName);
				}
			}
			dstPaths.put(dst, pathNodes);
		}
		
		return dstPaths;
	}
	
	/**
	 * Route a clock net with the default non-timing-driven approach.
	 * @param clk The clock net to be routed.
	 * @param device The design device.
	 */
	public static void defaultClkRoute(Net clk, Device device) {
		boolean debug = false;
 		boolean debugPrintPIPs = false;
 		if(debug) System.out.println("\nROUTE CLK NET...");
 		
		List<ClockRegion> clockRegions = new ArrayList<>();
		for(SitePinInst pin : clk.getPins()) {
			if(pin.isOutPin()) continue;
			Tile t = pin.getTile();
			ClockRegion cr = t.getClockRegion();
			if(!clockRegions.contains(cr)) clockRegions.add(cr);
		}
		if(debug) System.out.println("clock regions " + clockRegions);
		
		ClockRegion centroid = findCentroid(clk, device);
		if(debug) System.out.println(" centroid clock region is  \n \t" + centroid);
		
		RouteNode clkRoutingLine = UltraScaleClockRouting.routeBUFGToNearestRoutingTrack(clk);//HROUTE
		if(debug) System.out.println("route BUFG to nearest routing track: \n \t" + clkRoutingLine);
		if(debugPrintPIPs) printCLKPIPs(clk);
		
		if(debug) System.out.println("route To Centroid ");
		RouteNode centroidRouteNode = UltraScaleClockRouting.routeToCentroid(clk, clkRoutingLine, centroid);//VROUTE
		if(debug) System.out.println(" clk centroid route node is \n \t" + centroidRouteNode);
		if(debugPrintPIPs) printCLKPIPs(clk);
		
		/** Transition centroid from routing track to vertical distribution track */
		if(debug) System.out.println("transition Centroid To Distribution Line");
		RouteNode centroidDistNode = UltraScaleClockRouting.transitionCentroidToDistributionLine(clk,centroidRouteNode);
		if(debug) System.out.println(" centroid distribution node is \n \t" + centroidDistNode);
		if(debugPrintPIPs) printCLKPIPs(clk);
		
		/** routeCentroidToVerticalDistributionLines and routeCentroidToHorizontalDistributionLines could result in duplicated PIPs */
		if(debug) System.out.println("route Centroid To Vertical Distribution Lines");
		/** Each ClockRegion is not necessarily the one that each RouteNode value belongs to (same row is a must) */
		Map<ClockRegion, RouteNode> vertDistLines = UltraScaleClockRouting.routeCentroidToVerticalDistributionLines(clk,centroidDistNode, clockRegions);
		if(debug) {
			System.out.println(" clock region - vertical distribution node ");
			for(ClockRegion cr : vertDistLines.keySet()) System.out.println(" \t" + cr + " \t " + vertDistLines.get(cr));
		}
		if(debugPrintPIPs) printCLKPIPs(clk);
		
		if(debug) System.out.println("route Centroid To Horizontal Distribution Lines");
		List<RouteNode> distLines = new ArrayList<>();
		distLines.addAll(UltraScaleClockRouting.routeCentroidToHorizontalDistributionLines(clk, centroidDistNode, vertDistLines));
		if(debug) System.out.println(" dist lines are \n \t" + distLines);
		if(debugPrintPIPs) printCLKPIPs(clk);
		
		/** I changed this method to just map connected node to SitePinInsts */
		if(debug) System.out.println("get LCB Pin mappings");
		Map<RouteNode, ArrayList<SitePinInst>> lcbMappings = getLCBPinMappings(clk);
		
		if(debug) System.out.println("route distribution to LCBs");
		UltraScaleClockRouting.routeDistributionToLCBs(clk, distLines, lcbMappings.keySet());		
		if(debugPrintPIPs) printCLKPIPs(clk);
		
		if(debug) System.out.println("route LCBs to sinks");
		UltraScaleClockRouting.routeLCBsToSinks(clk, lcbMappings);
		if(debugPrintPIPs) printCLKPIPs(clk);
		
		Set<PIP> clkPIPsWithoutDuplication = new HashSet<>();
		clkPIPsWithoutDuplication.addAll(clk.getPIPs());
		clk.getPIPs().clear();
		clk.setPIPs(clkPIPsWithoutDuplication);
		
		if(debugPrintPIPs) {
			System.out.println("Final CLK routing");
			printCLKPIPs(clk);
		}
	}
	
	/**
	 * Routes a clock net by dividing the target clock regions into two groups and routes to the two groups with different centroid nodes.
	 * @param clk The clock to be routed.
	 * @param device The design device.
	 */
	public static void symmetricClkRouting(Net clk, Device device) {	
 		if(clkDebug) System.out.println("\nROUTE CLK NET...");
 		
		List<ClockRegion> clockRegions = getClockRegionsOfNet(clk);
		if(clkDebug) System.out.println("CLOCK REGIONS: " + clockRegions);
		
		ClockRegion centroid = findCentroid(clk, device);
		if(clkDebug) System.out.println(" centroid clock region is  \n \t" + centroid);
		
		RouteNode clkRoutingLine = UltraScaleClockRouting.routeBUFGToNearestRoutingTrack(clk);// first HROUTE
		if(clkDebug) System.out.println("ROUTE BUFG TO NEAREST ROUTING TRACK via the first HROUTE: \n \t" + clkRoutingLine);
		if(debugPrintClkPIPs) printCLKPIPs(clk);
		
		RouteNode centroidHRouteNode = UltraScaleClockRouting.routeToCentroidHRouteOrVRouteAboveBelowCentroid(clk, clkRoutingLine, centroid, true);
		if(clkDebug) {
			System.out.println("GET CENTROID HROUTE: " + centroidHRouteNode);}
		if(debugPrintClkPIPs) printCLKPIPs(clk);
		
		Node hroute = Node.getNode(centroidHRouteNode.getTile(), centroidHRouteNode.getWire());
		ClockRegion crHRoute = hroute.getTile().getClockRegion();
		System.out.println("clock region of the hroute: " + crHRoute);
		
		RouteNode vrouteUp;
		RouteNode vrouteDown;	
		/** Two VROUTEs going up and down */
		vrouteUp = UltraScaleClockRouting.routeToCentroidHRouteOrVRouteAboveBelowCentroid(clk, centroidHRouteNode, centroid.getNeighborClockRegion(1, 0), false);	
		if(clkDebug) {
			System.out.println("GET VROUTE UP:       " + vrouteUp);}
		if(debugPrintClkPIPs) printCLKPIPs(clk);
		
		vrouteDown = UltraScaleClockRouting.routeToCentroidHRouteOrVRouteAboveBelowCentroid(clk, centroidHRouteNode, centroid.getNeighborClockRegion(0, 0), false);
		if(clkDebug) {	
			System.out.println("GET VROUTE DOWN:     " + vrouteDown);
		}
		if(debugPrintClkPIPs) printCLKPIPs(clk);
		
		/** get two sets of clock regions, pick UP set for sinks in the resions with the same row as the centroid clock region */
		upDownClockRegions(clockRegions, centroid);
		
		List<RouteNode> upDownDistLines = new ArrayList<>();
		if(clkDebug) System.out.println("ROUTE UP VROUTE TO HDISTR LINEs:");
		List<RouteNode> upLines = routeFromDirectionalVRouteToHorizontalDistributionLines(clk, vrouteUp, upClockRegions, false);
		if(upLines != null) upDownDistLines.addAll(upLines);
		
		if(clkDebug) System.out.println("ROUTE DOWN VROUTE TO HDISTR LINEs:");
		List<RouteNode> downLines = routeFromDirectionalVRouteToHorizontalDistributionLines(clk, vrouteDown, downClockRegions, true);
		if(downLines != null) upDownDistLines.addAll(downLines);
		
		if(clkDebug) System.out.println("GET LCB PIN MAPPINGS");
		Map<RouteNode, ArrayList<SitePinInst>> lcbMappings = getLCBPinMappings(clk);
		
		if(clkDebug) System.out.println("ROUTE HDISTRs TO LCBs");
		UltraScaleClockRouting.routeDistributionToLCBs(clk, upDownDistLines, lcbMappings.keySet());		
		if(debugPrintClkPIPs) printCLKPIPs(clk);
		
		if(clkDebug) System.out.println("ROUTE LCB TO SINKS");
		UltraScaleClockRouting.routeLCBsToSinks(clk, lcbMappings);
		if(debugPrintClkPIPs) printCLKPIPs(clk);
		
		Set<PIP> clkPIPsWithoutDuplication = new HashSet<>();
		clkPIPsWithoutDuplication.addAll(clk.getPIPs());
		clk.getPIPs().clear();
		clk.setPIPs(clkPIPsWithoutDuplication);
		
		if(debugPrintClkPIPs) {
			System.out.println("FINAL CLK ROUTING");
			printCLKPIPs(clk);
		}
	}
	
	/**
	 * Gets clock regions of a net's sink pins.
	 * @param clk The net in question.
	 * @return A list of clock regions of the net's sink pins.
	 */
	private static List<ClockRegion> getClockRegionsOfNet(Net clk) {
		List<ClockRegion> clockRegions = new ArrayList<>();
		for(SitePinInst pin : clk.getPins()) {
			if(pin.isOutPin()) continue;
			Tile t = pin.getTile();
			ClockRegion cr = t.getClockRegion();
			if(!clockRegions.contains(cr)) clockRegions.add(cr);
		}
		return clockRegions;
	}
	
	/**
	 * Routes from a GLOBAL_VERTICAL_ROUTE to horizontal distribution lines.
	 * @param clk The clock net to be routed.
	 * @param vroute The node to start the route.
	 * @param clockRegions Target clock regions.
	 * @param down To indicate if is is routing to the group of top clock regions.
	 * @return A list of RouteNodes indicating the reached horizontal distribution lines.
	 */
	private static List<RouteNode> routeFromDirectionalVRouteToHorizontalDistributionLines(Net clk, RouteNode vroute, List<ClockRegion> clockRegions, boolean down) {
		RouteNode centroidDistNode = UltraScaleClockRouting.transitionCentroidToUpDownDistributionLine(clk, vroute, down);
		if(clkDebug) System.out.println(" transition distribution node is \n \t = " + centroidDistNode);
		
		if(centroidDistNode == null) return null;
		
		Map<ClockRegion, RouteNode> vertDistLines = UltraScaleClockRouting.routeCentroidToVerticalDistributionLines(clk, centroidDistNode, clockRegions);
		if(clkDebug) {
			System.out.println(" clock region - vertical distribution node ");
			for(ClockRegion cr : vertDistLines.keySet()) System.out.println(" \t" + cr + " \t " + vertDistLines.get(cr));
		}
		
		List<RouteNode> distLines = new ArrayList<>();
		distLines.addAll(UltraScaleClockRouting.routeCentroidToHorizontalDistributionLines(clk, centroidDistNode, vertDistLines));
		if(clkDebug) System.out.println(" dist lines are \n \t" + distLines);
		
		return distLines;
	}
	
	private static List<ClockRegion> upClockRegions = new ArrayList<>();
	private static List<ClockRegion> downClockRegions = new ArrayList<>();	
	private static void upDownClockRegions(List<ClockRegion> clockRegions, ClockRegion centroid){
		for(ClockRegion cr : clockRegions) {
			if(cr.getInstanceY() >= centroid.getInstanceY()) {
				upClockRegions.add(cr);
			}else {
				downClockRegions.add(cr);
			}
		}
	}
	
	/**
	 * Maps each sink SitePinInsts of a clock net to a leaf clock buffer node.
	 * @param clk The clock net in question.
	 * @return A map between leaf clock buffer nodes and sink SitePinInsts.
	 */
	private static Map<RouteNode, ArrayList<SitePinInst>> getLCBPinMappings(Net clk){
		Map<RouteNode, ArrayList<SitePinInst>> lcbMappings = new HashMap<>();
		for(SitePinInst p : clk.getPins()){
			if(p.isOutPin()) continue;
			Node n = null;/** n should be a node whose name ends with "CLK_LEAF" */
			for(Node prev : p.getConnectedNode().getAllUphillNodes()) {
				if(prev.getTile().equals(p.getSite().getIntTile())) {
					for(Node prevPrev : prev.getAllUphillNodes()) {
						if(prevPrev.getIntentCode() == IntentCode.NODE_GLOBAL_LEAF) {
							n = prevPrev;
							break;
						}
					}
				}
			}
			
			RouteNode rn = n != null? new RouteNode(n.getTile(), n.getWire()):null;
			if(rn == null) throw new RuntimeException("ERROR: No mapped LCB to SitePinInst " + p);
			ArrayList<SitePinInst> sinks = lcbMappings.get(rn);
			if(sinks == null){
				sinks = new ArrayList<>();
				lcbMappings.put(rn, sinks);
			}
			sinks.add(p);	
		}
		
		return lcbMappings;
	}
	
	private static void printCLKPIPs(Net clk) {
		System.out.println(" \t  used pips");
		for(PIP pip : clk.getPIPs()) {
			System.out.println(pip + ", bidirec? = " + pip.isBidirectional() + ", reversed? = " + pip.isReversed());
		}
		System.out.println();
	}
	
	/**
	 * Finds the centroid clock region of a clock net.
	 * @param clk The clock net of a design.
	 * @param device The device of the design.
	 * @return The centroid clock region of a clock net.
	 */
	private static ClockRegion findCentroid(Net clk, Device device) {
		HashSet<Point> sitePinInstTilePoints = new HashSet<>();	
		for(SitePinInst spi : clk.getPins()) {
			if(spi.isOutPin()) continue;
			ClockRegion c = spi.getTile().getClockRegion();
			sitePinInstTilePoints.add(new Point(c.getColumn(),c.getRow()));
		}	
		Point center = SmallestEnclosingCircle.getCenterPoint(sitePinInstTilePoints);
		ClockRegion c = device.getClockRegion(center.y, center.x);		
		return c;
	}
	
	
	private static HashSet<String> lutOutputPinNames;
	static {
		lutOutputPinNames = new HashSet<String>();
		for(String cle : new String[]{"L", "M"}){
			for(String pin : new String[]{"A", "B", "C", "D", "E", "F", "G", "H"}){
				lutOutputPinNames.add("CLE_CLE_"+cle+"_SITE_0_"+pin+"_O");
			}
		}
	}
	
	/**
	 * Routes a static net (GND or VCC).
	 * @param currNet The current static net to be routed.
	 * @param unavailableNodes A set of unavailable nodes.
	 * @param routethruHelper Instantiated RoutethruHelper to exclude routethru nodes.
	 */
	public static Map<SitePinInst, List<Node>> routeStaticNet(Net currNet, Set<Node> unavailableNodes){
		NetType netType = currNet.getType();
		Set<PIP> netPIPs = new HashSet<>();
		Map<SitePinInst, List<Node>> sinkPathNodes = new HashMap<>();
		Queue<RoutingNode> q = new LinkedList<>();
		Set<RoutingNode> visitedRoutingNodes = new HashSet<>();
		Set<RoutingNode> usedRoutingNodes = new HashSet<>();
		
		boolean debug = false;
		if(debug) {
			System.out.println("Net: " + currNet.getName());
		}
		
		for(SitePinInst sink : currNet.getPins()) {
			if(sink.isOutPin()) continue;
			int watchdog = 10000;	
			if(debug) {
				System.out.println("SINK: TILE = " + sink.getTile().getName() + " NODE = " + sink.getConnectedNode().toString());
			}		
			q.clear();
			visitedRoutingNodes.clear();
			List<Node> pathNodes = new ArrayList<>();			
			Node node = sink.getConnectedNode();
			if(debug) System.out.println(node);
			RoutingNode sinkRNode = RouterHelper.createRoutingNode(node, createdRoutingNodes);
			sinkRNode.setPrev(null);	
			q.add(sinkRNode);
			boolean success = false;
			while(!q.isEmpty()){
				RoutingNode routingNode = q.poll();
				visitedRoutingNodes.add(routingNode);		
				if(debug) System.out.println("DEQUEUE:" + routingNode);
				if(debug) System.out.println(", PREV = " + routingNode.getPrev() == null ? " null" : routingNode.getPrev());		
				if(success = isThisOurStaticSource(design, routingNode, netType, usedRoutingNodes)){		
					//trace back for a complete path
					if(debug){
						System.out.println("SINK: TILE = " + sink.getTile().getName() + " NODE = " + sink.getConnectedNode().toString());
						System.out.println("SOURCE " + routingNode.toString() + " found");
					}	
					while(routingNode != null){
						usedRoutingNodes.add(routingNode);// use routed RNodes as the source
						pathNodes.add(routingNode.getNode());
						
						if(debug) System.out.println("  " + routingNode.toString());
						routingNode = routingNode.getPrev();
					}
					Collections.reverse(pathNodes);
					sinkPathNodes.put(sink, pathNodes);
					if(debug){
						for(Node pathNode:pathNodes){
							System.out.println(pathNode.toString());
						}
					}
					break;
				}
				if(debug){
					System.out.println("KEEP LOOKING FOR A SOURCE...");
				}
				for(Node uphillNode : routingNode.getNode().getAllUphillNodes()){
					if(routeThruHelper.isRouteThru(uphillNode, routingNode.getNode())) continue;
					RoutingNode nParent = RouterHelper.createRoutingNode(uphillNode, createdRoutingNodes);
					if(nParent == null) continue;
					if(!pruneNode(nParent, unavailableNodes, visitedRoutingNodes)) {
						nParent.setPrev(routingNode);
						q.add(nParent);
					}
				}
				watchdog--;
				if(watchdog < 0) {
					break;
				}
			}
			if(!success){
				System.err.println("ERROR: Failed to route " + currNet.getName() + " pin " + sink.toString());
			}else{
				sink.setRouted(true);
			}
		}
		
		for(List<Node> nodes:sinkPathNodes.values()){
			netPIPs.addAll(RouterHelper.getPIPsFromListOfReversedNodes(nodes));
		}
		
		currNet.setPIPs(netPIPs);
		return sinkPathNodes;
	}
	
	/**
	 * Checks if a RoutingNode should be pruned.
	 * @param routingNode The RoutingNode in question.
	 * @param unavailableNodes A set of unavailable nodes.
	 * @param visitedRoutingNodes
	 * @return true, if the RoutingNode should not be considered as an available resource.
	 */
	private static boolean pruneNode(RoutingNode routingNode, Set<Node> unavailableNodes, Set<RoutingNode> visitedRoutingNodes){
		Node n = routingNode.getNode();
		IntentCode ic = n.getTile().getWireIntentCode(n.getWire());
		switch(ic){
			case NODE_GLOBAL_VDISTR:
			case NODE_GLOBAL_HROUTE:
			case NODE_GLOBAL_HDISTR:
			case NODE_HLONG:
			case NODE_VLONG:
			case NODE_GLOBAL_VROUTE:
			case NODE_GLOBAL_LEAF:
			case NODE_GLOBAL_BUFG:
				return true;
			default:
		}
		if(unavailableNodes.contains(n)) return true;
		if(visitedRoutingNodes.contains(routingNode)) return true;
		return false;
	}
	
	/**
	 * Determines if the given RoutingNode can serve as our sink.
	 * @param routingNode The RoutingNode in question.
	 * @param type The net type to designate the static source type.
	 * @return true if this sources is usable, false otherwise. 
	 */
	private static boolean isThisOurStaticSource(Design design, RoutingNode routingNode, NetType type, Set<RoutingNode> usedRoutingNodes){
		if(usedRoutingNodes != null && usedRoutingNodes.contains(routingNode))
			return true;
		Node node = routingNode.getNode();
		return isNodeUsableStaticSource(node, type, design);
	}
	
	/**
	 * This method handles queries during the static source routing process. 
	 * It determines if the node in question can be used as a source for the current NetType.
	 * @param routingNode The ResourceNode in question.
	 * @param type The NetType to indicate what kind of static source we need (GND/VCC).
	 * @return True if the pin is a hard source or an unused LUT output that can be repurposed as a source.
	 */
	private static boolean isNodeUsableStaticSource(Node node, NetType type, Design design){
		// We should look for 3 different potential sources
		// before we stop:
		// (1) GND_WIRE 
		// (2) VCC_WIRE 
		// (3) Unused LUT Outputs (A_0, B_0,...,H_0)
		String pinName = type == NetType.VCC ? Net.VCC_WIRE_NAME : Net.GND_WIRE_NAME;		
		if(node.getWireName().startsWith(pinName)){
			return true;
		}else if(lutOutputPinNames.contains(node.getWireName())){
			Site slice = node.getTile().getSites()[0];
			SiteInst i = design.getSiteInstFromSite(slice);			
			if(i == null) return true; // Site is not used
			char uniqueId = node.getWireName().charAt(node.getWireName().length()-3);
			Net currNet = i.getNetFromSiteWire(uniqueId + "_O");
			if(currNet == null) return true;
			if(currNet.getType() == type) return true;
			return false;
		}
		return false;
	}
	
	/**
	 * A lightweight useful class for different simple routing-related scenarios, 
	 * each {@link RoutingNode} object is associated to a {@link Node} object.
	 */
	static class RoutingNode{
		private Node node;
		private RoutingNode prev;
		private boolean isTareget;
		
		private float delayFromSource;
		
		RoutingNode (Node node){
			this.node = node;
			this.prev = null;
			this.isTareget = false;
			this.delayFromSource = 0;
		}
				
		public float getDelayFromSource() {
			return delayFromSource;
		}

		public void setDelayFromSource(float delayFromSource) {
			this.delayFromSource = delayFromSource;
		}

		public void setPrev(RoutingNode prev) {
			this.prev = prev;
		}
		
		public RoutingNode getPrev() {
			return this.prev;
		}
		
		public Node getNode() {
			return this.node;
		}

		public boolean isTareget() {
			return isTareget;
		}

		public void setTareget(boolean isTareget) {
			this.isTareget = isTareget;
		}
		
		public int hashCode() {
			return this.node.hashCode();
		}
		
		public String toString() {
			return this.node.toString() + ", accDly = " + this.delayFromSource;
		}
	}
	
}