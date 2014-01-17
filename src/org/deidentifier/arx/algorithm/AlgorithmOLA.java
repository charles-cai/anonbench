/*
 * Source code of our CBMS 2014 paper "A benchmark of globally-optimal 
 *      methods for the de-identification of biomedical data"
 *      
 * Copyright (C) 2014 Florian Kohlmayer, Fabian Prasser
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.deidentifier.arx.algorithm;

import java.util.Iterator;

import org.deidentifier.arx.framework.check.INodeChecker;
import org.deidentifier.arx.framework.check.history.History.PruningStrategy;
import org.deidentifier.arx.framework.lattice.Lattice;
import org.deidentifier.arx.framework.lattice.Node;

/**
 * This class provides an efficient implementation of the OLA algorithm.
 * 
 * K. El Emam et al. "A Globally Optimal k-Anonymity Method for the De-Identification of Health Data"
 * J Am Med Inform Assoc 2009;16:670-682
 * doi:10.1197/jamia.M3144
 * 
 * Our implementation was described in
 * Florian Kohlmayer*, Fabian Prasser*, et. al "Highly Efficient Optimal K-Anonymity For Biomedical Datasets"
 * Proceedings of the 25th IEEE International Symposium on Computer-Based Medical Systems (CBMS), June 2012.
 * 
 * @author Prasser, Kohlmayer
 */
public class AlgorithmOLA extends AbstractBenchmarkAlgorithm {

    /** The bitset. */
    public OLAFastBitSet[]       memoization      = null;

    /** The levelmask. */
    private long                 levelmask        = 0;

    /** The map. */
    private OLALatticeNodeMap    map              = null;

    /** The untagged count. */
    private int[]                untagged         = null;

    /** The count. */
    int                          count            = 0;

    /**
     * Instantiates a new OLA algorithm.
     * 
     * @param lattice the lattice
     * @param checker the checker
     */
    public AlgorithmOLA(final Lattice lattice, final INodeChecker checker) {
        
        super(lattice, checker);
        checker.getHistory().setPruningStrategy(PruningStrategy.ANONYMOUS);

        // Init the map
        map = new OLALatticeNodeMap(lattice.getMaximumGeneralizationLevels());
        final Node[][] levels = lattice.getLevels();
        for (int i = 0; i < levels.length; i++) {
            final Node[] nodes = levels[i];
            for (int j = 0; j < nodes.length; j++) {
                map.put(nodes[j].getTransformation(), nodes[j]);
            }
        }

        untagged = new int[lattice.getLevels().length];
        for (int i = 0; i < lattice.getLevels().length; i++) {
            untagged[i] = lattice.getLevels()[i].length;
        }
        memoization = new OLAFastBitSet[lattice.getSize()];
        for (int i = 0; i < memoization.length; i++) {
            memoization[i] = new OLAFastBitSet(memoization.length + 1);
        }
    }

    /**
     * Check and tag the node
     * @param node
     */
    public void checkAndTag(final Node node) {
        check(node);
        if (node.getInformationLoss() != null) {
            if (!node.isTagged()) doTag(node, true);
            node.setInformationLoss(node.getInformationLoss());
        } else {
            if (!node.isTagged()) doTag(node, false);
        }
    }

    /**
     * Check whether the sublattice needs to be processed
     * 
     * @param top the top
     * @param bottom the bottom
     * @return true, if successful
     */
    public boolean levelsNotPruned(final int top, final int bottom) {
        final long mask = ((2L << top) - 1L) ^ ((2L << (bottom)) - 1L) ^ (1L << top);
        if ((mask & levelmask) == mask) { return false; }
        return true;
    }

    /**
     * Checks the lattice.
     */
    @Override
    public void traverse() {

        // checkAndTagCenter();
        final int maxindex = lattice.getLevels().length - 1;
        kmin(lattice.getLevels()[0][0], lattice.getLevels()[maxindex][0]);

        // Highest Node in lattice is always anononymous in our case
        lattice.getLevels()[maxindex][0].setTagged();
        lattice.getLevels()[maxindex][0].setAnonymous(true);
    }

    /**
     * Performs tagging and housekeeping
     * 
     * @param node the node
     * @param anonymous the anonymous
     */
    private void doTag(final Node node, final boolean anonymous) {

        // Tag
        node.setTagged();
        node.setAnonymous(anonymous);

        untagged[node.getLevel()]--;
        if (untagged[node.getLevel()] == 0) {
            levelmask |= 1 << node.getLevel();
        }

        // Traverse
        if (anonymous) {
            for (final Node up : node.getSuccessors()) {
                if (!up.isTagged()) {
                    doTag(up, anonymous);
                }
            }
        } else {
            for (final Node down : node.getPredecessors()) {
                if (!down.isTagged()) {
                    doTag(down, anonymous);
                }
            }
        }
    }

    /**
     * Kmin()
     * 
     * @param bottom
     *            the bottom
     * @param top
     *            the top
     */
    private void kmin(final Node bottom, final Node top) {

        memoization[top.id].set(bottom.id);

        if ((top.getLevel() - bottom.getLevel()) > 1) {

            final int midLevel = (top.getLevel() + bottom.getLevel()) / 2;

            Iterator<Node> iter = null;
            iter = new OLASublatticeNodeIterator(map, bottom, top, midLevel);
            while (iter.hasNext()) {
                final Node mid = iter.next();
                processMidNode(bottom, top, mid);
            }
        } else { // topLevel - bottomLevel <= 1
            if (!(bottom.isTagged() && !bottom.isAnonymous())) {
                checkAndTag(bottom);
            }
        }
    }

    /**
     * Processes a mid node.
     * 
     * @param bottom
     *            the bottom
     * @param top
     *            the top
     * @param mid
     *            the mid
     */
    private void processMidNode(final Node bottom,
                                final Node top,
                                final Node mid) {

        if (!mid.isTagged()) {
            checkAndTag(mid);
        }

        Node newTop = null;
        Node newBottom = null;

        if (mid.isAnonymous()) {
            newTop = mid;
            newBottom = bottom;
        } else {
            newTop = top;
            newBottom = mid;
        }

        if (!memoization[newTop.id].get(newBottom.id)) {
            if (levelsNotPruned(newTop.getLevel(), newBottom.getLevel())) {
                kmin(newBottom, newTop);
            } else {
                if (!newBottom.isTagged()) {
                    checkAndTag(newBottom);
                } else if (!newTop.isTagged()) {
                    checkAndTag(newTop);
                }
            }
        }
    }
}