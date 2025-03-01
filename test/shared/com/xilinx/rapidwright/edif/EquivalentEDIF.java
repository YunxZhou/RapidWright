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

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;

/**
 * Verify that EDIF files are equivalent
 */
public class EquivalentEDIF {
    /**
     * Tests EDIFPropertyObjects for equivalence (EDIFName and EDIF property maps are equal and
     * contain equivalent data.
     * @param golden The reference object
     * @param test The test object
     * @return True if the two objects are equivalent
     */
    private static boolean equivalentEDIFPropObject(EDIFPropertyObject golden, EDIFPropertyObject test) {
        Assertions.assertEquals(golden.getEDIFName(), test.getEDIFName());
        Assertions.assertEquals(golden.getProperties().size(), test.getProperties().size());
        for(Map.Entry<EDIFName, EDIFPropertyValue> e : golden.getProperties().entrySet()) {
            EDIFPropertyValue testValue = test.getProperty(e.getKey().getName());
            Assertions.assertNotNull(testValue);
            Assertions.assertEquals(e.getValue().getType(), testValue.getType());
            Assertions.assertEquals(e.getValue().getValue(), testValue.getValue());
        }
        return true;
    }

    /**
     * Checks if the two provided EDIFCells are equivalent.  They are equivalent if they have the
     * same EDIFName, same number and equivalent objects of EDIFPorts, EDIFCellInsts and EDIFNets.
     * Within EDIFNets, it also checks that the EDIFPortInsts are equivalent.
     * @param golden The reference cell
     * @param test The test cell
     * @return True if the two cells are equivalent, false otherwise.
     */
    private static boolean equivalentEDIFCells(EDIFCell golden, EDIFCell test) {
        Assertions.assertTrue(equivalentEDIFPropObject(golden, test));
        Assertions.assertEquals(golden.getEDIFView(), test.getEDIFView());
        Assertions.assertEquals(golden.getPorts().size(), test.getPorts().size());
        for(EDIFPort port : golden.getPorts()) {
            EDIFPort testPort = test.getPort(port.getBusName());
            if(port.isBus()) {
                EDIFPort portCollision = test.getPort(port.getName());
                if(portCollision != null) {
                    testPort = portCollision;
                }
            }
            Assertions.assertNotNull(testPort);
            Assertions.assertTrue(equivalentEDIFPropObject(port, testPort));
            Assertions.assertEquals(port.getLeft(), testPort.getLeft());
            Assertions.assertEquals(port.getRight(), testPort.getRight());
        }
        Assertions.assertEquals(golden.getCellInsts().size(), test.getCellInsts().size());
        for(EDIFCellInst inst : golden.getCellInsts()) {
            EDIFCellInst testInst = test.getCellInst(inst.getName());
            Assertions.assertNotNull(testInst);
            Assertions.assertTrue(equivalentEDIFPropObject(inst, testInst));
            Assertions.assertEquals(inst.getViewref(), testInst.getViewref());
            Assertions.assertEquals(inst.getCellName(), testInst.getCellName());
            Assertions.assertEquals(inst.getCellType().getLibrary().getEDIFName(),
                                    testInst.getCellType().getLibrary().getEDIFName());
        }
        Assertions.assertEquals(golden.getNets().size(), test.getNets().size());
        for(EDIFNet net : golden.getNets()) {
            EDIFNet testNet = test.getNet(net.getName());
            Assertions.assertNotNull(testNet);
            Assertions.assertTrue(equivalentEDIFPropObject(net, testNet));
            Assertions.assertEquals(net.getPortInsts().size(), testNet.getPortInsts().size());
            for(EDIFPortInst pInst : net.getPortInsts()) {
                EDIFPortInst testPortInst = testNet.getPortInst(pInst.getCellInst(), pInst.getName());
                Assertions.assertNotNull(testPortInst);
                Assertions.assertEquals(pInst.getIndex(), testPortInst.getIndex());
            }
        }
        return true;
    }

    /**
     * Checkes that two EDIF netlists are equivalent
     * @param golden Reference netlist
     * @param test Netlist that is being tested
     * @return True if the two netlists have the same number of libraries, cells, ports, nets,
     * instances and port instances, false otherwise
     */
    public static boolean equivalentEDIFNetlists(EDIFNetlist golden, EDIFNetlist test) {
        Assertions.assertEquals(golden.getEDIFName(), test.getEDIFName());
        Assertions.assertTrue(equivalentEDIFPropObject(golden.getDesign(), test.getDesign()));
        Assertions.assertTrue(equivalentEDIFCells(golden.getDesign().getTopCell(),
                                                  test.getDesign().getTopCell()));
        Assertions.assertEquals(golden.getLibraries().size(), test.getLibraries().size());
        for(EDIFLibrary lib : golden.getLibraries()) {
            EDIFLibrary testLib = test.getLibrary(lib.getName());
            Assertions.assertNotNull(testLib);
            for(EDIFCell cell : lib.getCells()) {
                EDIFCell testCell = testLib.getCell(cell.getLegalEDIFName());
                Assertions.assertNotNull(testCell);
                Assertions.assertTrue(equivalentEDIFCells(cell, testCell));
            }
        }
        return true;
    }

    private static List<String> readEDIFLines(Path path){
        List<String> lines = new ArrayList<>();
        try(BufferedReader br = new BufferedReader(new FileReader(path.toFile()))){
            String line = null;
            while((line = br.readLine()) != null) {
                if(line.contains("(metax")) continue;
                if(line.contains("(timeStamp")) continue;
                lines.add(line);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return lines;
    }

    /**
     * Compares two text-based EDIF files to see if they match
     * (except for their respective timestamp)
     * @param golden Path to the golden EDIF file
     * @param test Path to the test EDIF file
     * @return True if the two files match (excluding timestamp), false otherwise
     */
    public static boolean compareEDIFFiles(Path golden, Path test) {
        List<String> goldenLines = readEDIFLines(golden);
        List<String> testLines = readEDIFLines(test);
        Collections.sort(goldenLines);
        Collections.sort(testLines);
        if(goldenLines.size() != testLines.size()) return false;
        int length = goldenLines.size();
        for(int i=0 ; i < length; i++) {
            if(!goldenLines.get(i).equals(testLines.get(i))) {
                System.err.println("EDIF mismatch on line " + i + ": >>"
                        + goldenLines.get(i) +"<<  >>" + testLines.get(i) + "<<");
                return false;
            }
        }
        return true;
    }
}
