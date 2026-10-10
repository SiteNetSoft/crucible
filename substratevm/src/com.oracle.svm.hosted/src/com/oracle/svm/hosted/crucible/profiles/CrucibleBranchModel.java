/*
 * Copyright (c) 2026, CrucibleVM contributors. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted.crucible.profiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.oracle.svm.core.crucible.CrucibleOptions;

/**
 * A model of how often the true side of a branch is taken, from the features of
 * {@link CrucibleBranchFeatures}: a sum of small decision trees, trained on the branches of
 * recorded runs by {@code crucible/ml/train-branches.py}, which writes the trees of
 * {@link CrucibleBranchModelData}.
 * <p>
 * A tree is a list of nodes, the root first. A node that is not a leaf asks whether one feature
 * is at most a threshold and names the node to go on to either way; a leaf holds what the tree
 * adds to the sum.
 */
public final class CrucibleBranchModel {

    private static final int[][] FEATURE;
    private static final double[][] THRESHOLD;
    private static final int[][] LEFT;
    private static final int[][] RIGHT;
    private static final double[][] VALUE;

    private static final String FEATURES;
    private static final double BASE;

    static {
        String[] trees = CrucibleBranchModelData.TREES;
        String features = CrucibleBranchModelData.FEATURES;
        double base = CrucibleBranchModelData.BASE;
        String file = CrucibleOptions.CrucibleBranchModelFile.getValue();
        if (!file.isEmpty()) {
            /* A model in a file: the features on the first line, the base on the second, then a tree a line. */
            try {
                List<String> lines = Files.readAllLines(Path.of(file), StandardCharsets.UTF_8);
                features = lines.get(0);
                base = Double.parseDouble(lines.get(1));
                trees = lines.subList(2, lines.size()).toArray(new String[0]);
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("Cannot read the branch model " + file, e);
            }
        }
        FEATURES = features;
        BASE = base;
        FEATURE = new int[trees.length][];
        THRESHOLD = new double[trees.length][];
        LEFT = new int[trees.length][];
        RIGHT = new int[trees.length][];
        VALUE = new double[trees.length][];
        for (int t = 0; t < trees.length; t++) {
            String[] nodes = trees[t].split(";");
            FEATURE[t] = new int[nodes.length];
            THRESHOLD[t] = new double[nodes.length];
            LEFT[t] = new int[nodes.length];
            RIGHT[t] = new int[nodes.length];
            VALUE[t] = new double[nodes.length];
            for (int n = 0; n < nodes.length; n++) {
                String[] parts = nodes[n].split(":");
                if (parts.length == 1) {
                    FEATURE[t][n] = -1;
                    VALUE[t][n] = Double.parseDouble(parts[0]);
                } else {
                    FEATURE[t][n] = Integer.parseInt(parts[0]);
                    THRESHOLD[t][n] = Double.parseDouble(parts[1]);
                    LEFT[t][n] = Integer.parseInt(parts[2]);
                    RIGHT[t][n] = Integer.parseInt(parts[3]);
                }
            }
        }
    }

    private CrucibleBranchModel() {
    }

    /** Whether a model was trained for exactly the features this build computes. */
    public static boolean isAvailable() {
        return FEATURE.length > 0 && FEATURES.equals(String.join(",", CrucibleBranchFeatures.NAMES));
    }

    /** The predicted probability of the true side, or NaN without a model. */
    public static double predict(double[] features) {
        if (FEATURE.length == 0) {
            return Double.NaN;
        }
        double sum = BASE;
        for (int t = 0; t < FEATURE.length; t++) {
            int[] feature = FEATURE[t];
            int node = 0;
            while (feature[node] >= 0) {
                node = features[feature[node]] <= THRESHOLD[t][node] ? LEFT[t][node] : RIGHT[t][node];
            }
            sum += VALUE[t][node];
        }
        return sum;
    }
}
