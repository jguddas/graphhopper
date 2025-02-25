/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.routing.util;

import com.graphhopper.ResponsePath;
import com.graphhopper.routing.AlgorithmOptions;
import com.graphhopper.routing.Path;
import com.graphhopper.routing.RoutingAlgorithm;
import com.graphhopper.routing.RoutingAlgorithmFactorySimple;
import com.graphhopper.routing.querygraph.QueryGraph;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.Graph;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.*;
import com.graphhopper.util.shapes.GHPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * @author Peter Karich
 */
public class TestAlgoCollector {
    public final List<String> errors = new ArrayList<>();
    private final String name;
    private final DistanceCalc distCalc = DistanceCalcEarth.DIST_EARTH;
    private final TranslationMap trMap = new TranslationMap().doImport();

    public TestAlgoCollector(String name) {
        this.name = name;
    }

    public TestAlgoCollector assertDistance(EncodingManager encodingManager, AlgoHelperEntry algoEntry, List<Snap> queryList,
                                            OneRun oneRun) {
        List<Path> altPaths = new ArrayList<>();
        QueryGraph queryGraph = QueryGraph.create(algoEntry.graph, queryList);
        for (int i = 0; i < queryList.size() - 1; i++) {
            RoutingAlgorithm algo = algoEntry.createAlgo(queryGraph);

//            if (!algoEntry.getExpectedAlgo().equals(algo.toString())) {
//                errors.add("Algorithm expected " + algoEntry.getExpectedAlgo() + " but was " + algo.toString());
//                return this;
//            }

            Path path = algo.calcPath(queryList.get(i).getClosestNode(), queryList.get(i + 1).getClosestNode());
            altPaths.add(path);
        }

        PathMerger pathMerger = new PathMerger(queryGraph.getBaseGraph(), algoEntry.getWeighting()).
                setCalcPoints(true).
                setSimplifyResponse(false).
                setEnableInstructions(true);
        ResponsePath responsePath = pathMerger.doWork(new PointList(), altPaths, encodingManager, trMap.getWithFallBack(Locale.US));

        if (responsePath.hasErrors()) {
            errors.add("response for " + algoEntry + " contains errors. Expected distance: " + oneRun.getDistance()
                    + ", expected points: " + oneRun + ". " + queryList + ", errors:" + responsePath.getErrors());
            return this;
        }

        PointList pointList = responsePath.getPoints();
        double tmpDist = distCalc.calcDistance(pointList);
        if (Math.abs(responsePath.getDistance() - tmpDist) > 2) {
            errors.add(algoEntry + " path.getDistance was  " + responsePath.getDistance()
                    + "\t pointList.calcDistance was " + tmpDist + "\t (expected points " + oneRun.getLocs()
                    + ", expected distance " + oneRun.getDistance() + ") " + queryList);
        }

        if (Math.abs(responsePath.getDistance() - oneRun.getDistance()) > 2) {
            errors.add(algoEntry + " returns path not matching the expected distance of " + oneRun.getDistance()
                    + "\t Returned was " + responsePath.getDistance() + "\t (expected points " + oneRun.getLocs()
                    + ", was " + pointList.getSize() + ") " + "\t (weight " + responsePath.getRouteWeight() + ") " + queryList);
        }

        // There are real world instances where A-B-C is identical to A-C (in meter precision).
        if (Math.abs(pointList.getSize() - oneRun.getLocs()) > 1) {
            errors.add(algoEntry + " returns path not matching the expected points of " + oneRun.getLocs()
                    + "\t Returned was " + pointList.getSize() + "\t (expected distance " + oneRun.getDistance()
                    + ", was " + responsePath.getDistance() + ") " + queryList);
        }
        return this;
    }

    void queryIndex(Graph g, LocationIndex idx, double lat, double lon, double expectedDist) {
        Snap res = idx.findClosest(lat, lon, EdgeFilter.ALL_EDGES);
        if (!res.isValid()) {
            errors.add("node not found for " + lat + "," + lon);
            return;
        }

        GHPoint found = res.getSnappedPoint();
        double dist = distCalc.calcDist(lat, lon, found.lat, found.lon);
        if (Math.abs(dist - expectedDist) > .1) {
            errors.add("queried lat,lon=" + (float) lat + "," + (float) lon
                    + " (found: " + (float) found.lat + "," + (float) found.lon + ")"
                    + "\n   expected distance:" + expectedDist + ", but was:" + dist);
        }
    }

    @Override
    public String toString() {
        String str = "";
        str += "FOUND " + errors.size() + " ERRORS.\n";
        for (String s : errors) {
            str += s + ".\n";
        }
        return str;
    }

    void printSummary() {
        if (errors.size() > 0) {
            System.out.println("\n-------------------------------\n");
            System.out.println(toString());
        } else {
            System.out.println("SUCCESS for " + name + "!");
        }
    }

    public static class AlgoHelperEntry {
        private final LocationIndex idx;
        private Graph graph;
        private boolean ch;
        private String expectedAlgo;
        private Weighting weighting;
        private AlgorithmOptions opts;

        public AlgoHelperEntry(Graph g, boolean ch, Weighting weighting, AlgorithmOptions opts, LocationIndex idx, String expectedAlgo) {
            this.graph = g;
            this.ch = ch;
            this.weighting = weighting;
            this.opts = opts;
            this.idx = idx;
            this.expectedAlgo = expectedAlgo;
        }

        public RoutingAlgorithm createAlgo(Graph graph) {
            return new RoutingAlgorithmFactorySimple().createAlgo(graph, weighting, opts);
        }

        public Weighting getWeighting() {
            return weighting;
        }

        public LocationIndex getIdx() {
            return idx;
        }

        public String getExpectedAlgo() {
            return expectedAlgo;
        }

        @Override
        public String toString() {
            String algo = opts.getAlgorithm();
            if (getExpectedAlgo().contains("landmarks"))
                algo += "|landmarks";
            if (ch)
                algo += "|ch";

            return "algoEntry(" + algo + ")";
        }
    }

    public static class OneRun {
        private final List<AssumptionPerPath> assumptions = new ArrayList<>();

        public OneRun() {
        }

        public OneRun(double fromLat, double fromLon, double toLat, double toLon, double dist, int locs) {
            add(fromLat, fromLon, 0, 0);
            add(toLat, toLon, dist, locs);
        }

        public OneRun add(double lat, double lon, double dist, int locs) {
            assumptions.add(new AssumptionPerPath(lat, lon, dist, locs));
            return this;
        }

        public int getLocs() {
            int sum = 0;
            for (AssumptionPerPath as : assumptions) {
                sum += as.locs;
            }
            return sum;
        }

        public void setLocs(int index, int locs) {
            assumptions.get(index).locs = locs;
        }

        public double getDistance() {
            double sum = 0;
            for (AssumptionPerPath as : assumptions) {
                sum += as.distance;
            }
            return sum;
        }

        public void setDistance(int index, double dist) {
            assumptions.get(index).distance = dist;
        }

        public List<Snap> getList(LocationIndex idx, EdgeFilter edgeFilter) {
            List<Snap> snap = new ArrayList<>();
            for (AssumptionPerPath p : assumptions) {
                snap.add(idx.findClosest(p.lat, p.lon, edgeFilter));
            }
            return snap;
        }

        @Override
        public String toString() {
            return assumptions.toString();
        }
    }

    static class AssumptionPerPath {
        double lat, lon;
        int locs;
        double distance;

        public AssumptionPerPath(double lat, double lon, double distance, int locs) {
            this.lat = lat;
            this.lon = lon;
            this.locs = locs;
            this.distance = distance;
        }

        @Override
        public String toString() {
            return lat + ", " + lon + ", locs:" + locs + ", dist:" + distance;
        }
    }
}
