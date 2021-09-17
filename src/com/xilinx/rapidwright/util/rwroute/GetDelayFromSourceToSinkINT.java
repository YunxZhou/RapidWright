package com.xilinx.rapidwright.util.rwroute;

import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.rwroute.RouterHelper;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import com.xilinx.rapidwright.timing.delayestimator.InterconnectInfo;
import com.xilinx.rapidwright.util.Pair;

/**
 * A helper class to write delay from the source to the sink of a routed net
 * (Usage: input.dcp --net net_name outputFilePath --allSinkDelay).
 * When the "--allSinkDelay" option specified, it writes the delay values from 
 * the source to the sinks (until the interconnect tile node) of the net under the name.
 * Otherwise, it is specifically for the CLK_IN sink of the net under the name.
 */
public class GetDelayFromSourceToSinkINT {
	public static void main(String[] args) {
		if(args.length < 4){
			System.out.println("BASIC USAGE:\n <input.dcp> --net <net name> <output file> --allSinkDelay\n");
			return;
		}
		
		boolean writeAllSinkDelay = args.length > 4 && args[4].equals("--allSinkDelay");
		
		String inputDcpName = args[0].substring(args[0].lastIndexOf("/")+1);
		Design design = Design.readCheckpoint(args[0]);
		boolean useUTurnNodes = false;
		DelayEstimatorBase estimator = new DelayEstimatorBase(design.getDevice(), new InterconnectInfo(), useUTurnNodes, 0);
		
		Net net = design.getNet(args[2]);
		if(net == null) {
			System.err.println("ERROR: Cannot find net under name " + args[2]);
			return;
		}else if(!net.hasPIPs()) {
			System.err.println("ERROR: No PIPs found of net " + net.getName());
			return;
		}
		
		Map<Pair<SitePinInst, Node>, Short> sourceToSinkINTDelays = RouterHelper.getSourceToSinkINTNodeDelays(net, estimator);
		
		String outputFile = args[3].endsWith("/")? args[3] : args[3] + "/";
		outputFile += inputDcpName.replace(".dcp", "_getDelayToSinkINT.txt");	
		
		try {
			FileWriter myWriter = new FileWriter(outputFile);
			
			if(writeAllSinkDelay) {
				System.out.println("INFO: Write delay from source to all sink to file \n      " + outputFile);
				for(Pair<SitePinInst, Node> sinkINTNode : sourceToSinkINTDelays.keySet()) {
					myWriter.write(sinkINTNode.getSecond() + " \t\t" + sourceToSinkINTDelays.get(sinkINTNode) + "\n");
					System.out.printf(String.format("      %-50s %5d\n", sinkINTNode.getSecond(), sourceToSinkINTDelays.get(sinkINTNode)));		
				}
				
			}else {
				System.out.println("INFO: Write delay from source to IMUX node of CLK_IN to file \n      " + outputFile);
				for(Pair<SitePinInst, Node> sinkINTNode : sourceToSinkINTDelays.keySet()) {
					if(sinkINTNode.getFirst().toString().contains("CLK_IN")) {
						myWriter.write(sinkINTNode.getSecond() + " \t\t" + sourceToSinkINTDelays.get(sinkINTNode) + "\n");
						System.out.printf(String.format("      %-50s %5d\n", sinkINTNode.getSecond(), sourceToSinkINTDelays.get(sinkINTNode)));
					}
				}
			}
				
			myWriter.close();
			
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
}
