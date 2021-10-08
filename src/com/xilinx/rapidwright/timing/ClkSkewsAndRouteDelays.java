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

package com.xilinx.rapidwright.timing;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.util.Pair;

/**
 * Creating a CLKSkewRouteDelay instance parses a supplied text file and stores clock skew, route, and delay data into maps to use.
 * To obtain the text file, please refer to find_clock_tree_template.tcl under $RAPIDWRIGHT_PATH/tcl/rwroute.
 * When the file is ready, please use "--clkSkew>" option (see {@link Configuration})
 * to enable RWRoute to use the file for clock skew-aware timing-driven clock routing.
 */
public class ClkSkewsAndRouteDelays {
	/** Name of the file */
	private String name;
	/** Mapping from pairs of clock regions (starting and ending clock regions of a timing path) and the skew data */
	private Map<Pair<String, String>, List<Short>> skew;
	// skew
	// # src  	dst  	skew  	src_dly  	dst_dly  	pess
	// X2Y3  	X3Y3    -14 	2889 		2439  		435
	/** Partial route from BUFGCE out to each clock region */
	private Map<String, List<String>> routesToDestinationClockRegions;
	// route
	// X2Y3  RCLK_CLEM_L_X52Y149/CLK_CMT_MUX_3TO1_1_CLK_OUT  RCLK_CLEM_L_X52Y209/CLK_VDISTR_BOT  ...
	/** Settings of buffer delay taps */
	private Map<Pair<String, String>, List<Short>> delay;
	// delay
	// # to  row_buf (SITE)       	row_tap 	leaf_tap   src_@0.3 	src_@0.6 	src_@0.9   dst_@0.3 	dst_@0.6 	dst_@0.9
	// X2Y3  BUFCE_ROW_FSR_X91Y3 	0       	0          0        	0        	0          0        	0        	0
	
	private String clkSkewRouteDelayFile;
	
	public ClkSkewsAndRouteDelays(String fileName) {
		this.name = fileName;	
		this.skew = new HashMap<>();
		this.routesToDestinationClockRegions = new HashMap<>();
		this.delay = new HashMap<>();
		
		if(fileName != null) {
			clkSkewRouteDelayFile = fileName;
			System.out.println("INFO: Clock skew-route-delay file set as: " + clkSkewRouteDelayFile);
		}
		
		try {
			this.parseDataFromFile();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	private void parseDataFromFile() throws IOException {
		File clkTimingFile = new File(this.name);
		if(!clkTimingFile.exists()) {
        	throw new IllegalArgumentException("ERROR: Specified clock skew-route-delay file does not exist.");
        }
		BufferedReader reader = new BufferedReader(new FileReader(clkTimingFile));
		// NOTE: Different data sections (skew, route, delay, etc) must be read into map following the same order as shown in the file
		this.parseDataSection(reader, "skew");
		this.parseDataSection(reader, "route");
		this.parseDataSection(reader, "delay");
		reader.close();
	}
	
	private void parseDataSection(BufferedReader reader, String section) throws IOException {
		boolean dataFound = false;
		//check if the data is complete
		String startLineToFind = section;
        String line;
        try {
			while ((line = reader.readLine()) != null) {
			    if (line.equals(startLineToFind)) {
			        dataFound = true;
			        break;
			    }
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
        
        if(!dataFound) {
        	throw new IllegalArgumentException("ERROR: No section header found in the supplied file for " + section);
        }
        if(section.equals("skew")) {
        	this.readSkewDelayToMap(reader, this.skew);
        }else if(section.equals("route")) {
        	this.readRouteToMap(reader, this.routesToDestinationClockRegions);
        }else if(section.equals("delay")) {
        	this.readSkewDelayToMap(reader, this.delay);
        } 
	}
	
	private void readSkewDelayToMap(BufferedReader reader, Map<Pair<String, String>, List<Short>> toMap) throws IOException {
		String line;
		while ((line = reader.readLine()) != null) {
			if(line.startsWith("#")) continue;
			if (line.length() == 0) {
				// the end of the current section
				return;
			 }
			String[] dataStrings = line.split("\\s+");
			if(dataStrings.length <= 2) {
				throw new IllegalArgumentException("ERROR: Incomplete data of line: " + line);
			}
			Pair<String, String> key = new Pair<>(dataStrings[0], dataStrings[1]);
			List<Short> values = new ArrayList<>();
			for(int i = 2; i < dataStrings.length; i++) {
				values.add(Short.parseShort(dataStrings[i]));
			}
			toMap.put(key, values);
		}
	}
	
	private void readRouteToMap(BufferedReader reader, Map<String, List<String>> toMap) throws IOException {
		String line;
		while ((line = reader.readLine()) != null) {
			if (line.length() == 0) {
				return;
			}
			
			if(line.startsWith("#")) continue;
			String[] dataStrings = line.split("\\s+");
			if(dataStrings.length <= 1) {
				throw new IllegalArgumentException("ERROR: Incomplete data of line: " + line);
			}
			
			List<String> values = new ArrayList<>();
			for(int i = 1; i < dataStrings.length; i++) {
				values.add(dataStrings[i]);
			}
			toMap.put(dataStrings[0], values);
		}
	}

	public Map<Pair<String, String>, List<Short>> getSkew() {
		return skew;
	}

	public Map<String, List<String>> getRoute() {
		return routesToDestinationClockRegions;
	}

	public Map<Pair<String, String>, List<Short>> getDelay() {
		return delay;
	}
	
}