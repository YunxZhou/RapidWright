/* 
 * Copyright (c) 2022 Xilinx, Inc. 
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
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
 
package com.xilinx.rapidwright.edif;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestEDIFHierNet {

    @Test
    public void testGetLeafHierPortInsts() {
        Design design = RapidWrightDCP.loadDCP("bnn.dcp");
        
        EDIFNetlist netlist = design.getNetlist();
        
        for(EDIFHierNet parentNet : netlist.getParentNetMap().values()) {
            Set<EDIFHierPortInst> goldSet = new HashSet<>(netlist.getPhysicalPins(parentNet)); 
            Set<EDIFHierPortInst> testSet = new HashSet<>(parentNet.getLeafHierPortInsts());
            Assertions.assertEquals(goldSet.size(), testSet.size());
            
            for(EDIFHierPortInst portInst : goldSet) {
                Assertions.assertTrue(testSet.remove(portInst));
            }
            Assertions.assertTrue(testSet.isEmpty());
        }
    }
}
