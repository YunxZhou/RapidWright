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
import java.util.List;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;

/**
 * A class extends {@link RWRoute} for partial routing.
 */
public class PartialRouter2 extends RWRoute{
	public PartialRouter2(Design design, Configuration config){
		super(design, config);
	}
	
	protected void determineRoutingTargets() {
		this.categorizeNets();
		if(this.getConflictNets().size() > 0)
			System.out.println("CRITICAL WARNING: Conficting nets: " + this.getConflictNets().size());
		this.handleConflictNets(this.getDesign());
		if(this.config.isPrintConnectionSpan()) this.printConnectionSpanStatistics();
	}
	
	private void handleConflictNets(Design design) {
		//conflict nets are consistent with what recognized in RW
//		List<Net> vivadoConflictNets = this.readDesignNetsFromFile(design, "vivado_conflict_nets.txt");
		
//		List<Net> vivadoUnouteNets = this.readDesignNetsFromFile(design, "unrouted_anchor_nets_vivado.txt");
		// TODO Vivado dumped net names cannot be recognized in RW, see vivadoUnrouteNets
//		System.out.println(design.getNet("w_IO_L2_in142_U0_fifo_w_out_V_V_din_pass_0_out[30]"));
		
		List<Net> toPreserveNets = new ArrayList<>();
		for(Net net : this.getConflictNets()) {
			if(net.getType() != NetType.WIRE) {//skip clk, vcc and gnd
				System.out.println("PRESERVE NON-WIRE NET: " + net);
				toPreserveNets.add(net);
				continue;
			}
			
			if(net.getSinkPins().size() > 1) {
				toPreserveNets.add(net);
				System.out.println("PRESERVE NON FF-FF NET (fanout > 1): " + net.getName());
				continue;
			}
			
			if(!this.isNonLagunaAnchorNet(net, design)) {
				System.out.println("PRESERVE NON ANCHOR NET: " + net.getName());
				toPreserveNets.add(net);
				continue;
			}
			
			this.removeNetNodesFromPreservedNodes(net); // remove preserved nodes of a net from the map
			this.createsNetWrapperAndConnections(net, this.config.getBoundingBoxExtensionX(), this.config.getBoundingBoxExtensionY(), this.isMultiSLRDevice());
			net.unroute();//NOTE: do not unroute if routing tree is reused, then toPreserveNets should be detected before createNetWrapperAndConnections
		}
		for(Net net : toPreserveNets) {
			this.preserveNet(net);
		}
	}
	
	private boolean isNonLagunaAnchorNet(Net net, Design design) {
		boolean anchorNet = false;
		List<EDIFHierPortInst> ehportInsts = design.getNetlist().getPhysicalPins(net.getName());
		boolean input = false;
		if(ehportInsts == null) { //DSP related nets
			return false;
		}
		for(EDIFHierPortInst eport : ehportInsts) {
			if(eport.toString().contains("q0_reg")) {//q0_reg for identifying anchor nets, more efficient way to check?
				anchorNet = true;
				if(eport.isInput()) input = true;
				break;//are there nets connecting CLE anchor and LAG anchor?
			}
		}
		Tile anchorTile = null;
		if(input) {
			anchorTile = net.getSinkPins().get(0).getTile();
		}else {
			anchorTile = net.getSource().getTile();
		}
		// if laguna anchor nets are never conflicted, we do not need to check tile.
		return anchorNet && anchorTile.getName().startsWith("CLE");
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
			this.createsNetWrapperAndConnections(net, this.config.getBoundingBoxExtensionX(),this.config.getBoundingBoxExtensionY(), multiSLR);
		}else{
			// In partial routing mode, a net with PIPs is preserved.
			// This means the routed net is supposed to be fully routed without conflicts.
			// TODO detect partially routed nets
			this.preserveNet(net);
			this.increaseNumPreservedWireNets();
		}
	}
}


