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

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPortInst;

/**
 * A class extends {@link RWRoute} for partial routing.
 */
public class PartialRouter2 extends RWRoute{
	public PartialRouter2(Design design, Configuration config){
		super(design, config);
		
		//conflict nets are consistent with what recognized in RW
//		List<Net> vivadoConflictNets = this.readDesignNetsFromFile(design, "/home/yun/Desktop/vivado_conflict_nets.txt");
		
//		List<Net> vivadoUnouteNets = this.readDesignNetsFromFile(design, "/home/yun/Desktop/unrouted_anchor_nets_vivado.txt");
		//TODO 
//		System.out.println(design.getNet("w_IO_L2_in142_U0_fifo_w_out_V_V_din_pass_0_out[30]"));
		
		List<Net> toPreserveNets = new ArrayList<>();
		for(Net net : this.getConflictNets()) {
			if(net.getType() != NetType.WIRE) {//skip clk, vcc and gnd
				System.out.println("SKIP NON WIRE NET: " + net);
				toPreserveNets.add(net);
				continue;
			}
			if(!RouterHelper.isRoutableNetWithSourceSinks(net)) {
				toPreserveNets.add(net);
				System.out.println("SKIP NET WITHOUT BOTH SOURCE AND SINKS: " + net.getName());
				continue;
			}
			if(net.getSinkPins().size() > 1) {
				//TODO fanout > 1 may not work for other DCPs, need a good way to detect anchor nets
				// TODO Vivado dumped net names cannot be recognized in RW, see vivadoUnrouteNets
				toPreserveNets.add(net);
				System.out.println("SKIP NON FF-FF NET (fanout > 1): " + net.getName());
				continue;
			}
			
			this.removeNetNodesFromPreservedNodes(net); // remove preserved nodes of a net from the map
			this.createsNetWrapperAndConnectionsReuse(net, this.config.getBoundingBoxExtension(), this.isMultiSLRDevice());
			net.unroute();//TODO do not unroute, reuse routes
		}
		for(Net net : toPreserveNets) {
			this.preserveNet(net);
		}
	}
	
	@Override
	protected void addGlobalClkRoutingTargets(Net clk) {
		if(!clk.hasPIPs()) {
			if(RouterHelper.isRoutableNetWithSourceSinks(clk)) {
				this.addClkNet(clk);
			}else {
				this.increaseNumNotNeedingRouting();
				System.err.println("ERROR: Incomplete clk net " + clk.getName());
			}
		}else {
			this.preserveNet(clk);
			this.increaseNumPreservedClks();
		}
	}
	
	@Override
	protected void addStaticNetRoutingTargets(Net staticNet){
		List<SitePinInst> sinks = new ArrayList<>();
		for(SitePinInst sink : staticNet.getPins()){
			if(sink.isOutPin()) continue;
			sinks.add(sink);
		}
		
		if(sinks.size() > 0 ) {
			if(!staticNet.hasPIPs()) {
				for(SitePinInst sink : sinks) {
					this.addReservedNode(sink.getConnectedNode(), staticNet);
				}
				this.addStaticNetRoutingTargets(staticNet, sinks);
			}else {
				this.preserveNet(staticNet);
				this.increaseNumPreservedStaticNets();
			}	
			
		}else {// internally routed (sinks.size = 0)
			this.preserveNet(staticNet);
			this.increaseNumNotNeedingRouting();
		}
	}
	
	@Override
	protected void addNetConnectionToRoutingTargets(Net net, boolean multiSLR) {
		if(!net.hasPIPs()) {
			this.createsNetWrapperAndConnections(net, this.config.getBoundingBoxExtension(), multiSLR);
		}else{
			// In partial routing mode, a net with PIPs is preserved.
			// This means the routed net is supposed to be fully routed without conflicts.
			// TODO detect partially routed nets and nets with possible conflicting nodes.
			this.preserveNet(net);
			this.increaseNumPreservedWireNets();
		}
	}
	
	///home/yun/Desktop/unrouted_anchor_nets_vivado.txt
	private List<Net> readDesignNetsFromFile(Design design, String textFile) {
		List<Net> vivadoConflictNets = new ArrayList<>();
		int i = 0;
		try {
			BufferedReader myReader = new BufferedReader(new FileReader(textFile));
			String line;
			while((line = myReader.readLine()) != null) {
				if (line.length() == 0) {
					break;
				}
				String[] netName = line.split("\\s+");
				String name = netName[0];
				Net net = design.getNet(name);
				if(net == null) {
//					System.out.println("NULL: " + name);
				}else if(!RouterHelper.isRoutableNetWithSourceSinks(net)) {
					System.out.println("NOT ROUTABLE: " + name);
					System.out.println(net.toStringFull());
				}else {
					vivadoConflictNets.add(net);
				}
				i++;
			}
			myReader.close();
			System.out.println("Successfully read lines of file: " + i);
		} catch (IOException e) {
			System.out.println("An error occurred when reading file.");
			e.printStackTrace();
		}
		
		return vivadoConflictNets;
	}
	
}


