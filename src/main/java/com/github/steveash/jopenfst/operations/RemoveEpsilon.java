/*
 * Copyright 2014 Steve Ash
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
 */

package com.github.steveash.jopenfst.operations;

import com.google.common.base.Preconditions;

import com.github.steveash.jopenfst.Arc;
import com.github.steveash.jopenfst.Fst;
import com.github.steveash.jopenfst.MutableFst;
import com.github.steveash.jopenfst.MutableState;
import com.github.steveash.jopenfst.State;
import com.github.steveash.jopenfst.semiring.Semiring;

import java.util.HashMap;

/**
 * Remove epsilon operation.
 *
 * @author John Salatas <jsalatas@users.sourceforge.net>
 */
public class RemoveEpsilon {

  /**
   * Default Constructor
   */
  private RemoveEpsilon() {
  }

  /**
   * Put a new state in the epsilon closure
   */
  private static void put(State fromState, State toState, double weight,
                          HashMap<Integer, Double>[] cl) {
    HashMap<Integer, Double> tmp = cl[fromState.getId()];
    if (tmp == null) {
      tmp = new HashMap<>();
      cl[fromState.getId()] = tmp;
    }
    tmp.put(toState.getId(), weight);
  }

  /**
   * Add a state in the epsilon closure
   */
  private static void add(State fromState, State toState, double weight,
                          HashMap<Integer, Double>[] cl, Semiring semiring) {
    Double old = getPathWeight(fromState, toState, cl);
    if (old == null) {
      put(fromState, toState, weight, cl);
    } else {
      put(fromState, toState, semiring.plus(weight, old), cl);
    }

  }

  /**
   * Calculate the epsilon closure
   */
  private static void calcClosure(Fst fst, State state,
                                  HashMap<Integer, Double>[] cl, Semiring semiring, int iEps, int oEps) {
    State s = state;

    double pathWeight;
    int numArcs = s.getArcCount();
    for (int j = 0; j < numArcs; j++) {
      Arc a = s.getArc(j);
      if ((a.getIlabel() == iEps) && (a.getOlabel() == oEps)) {
        if (cl[a.getNextState().getId()] == null) {
          calcClosure(fst, a.getNextState(), cl, semiring, iEps, oEps);
        }
        if (cl[a.getNextState().getId()] != null) {
          for (Integer pathFinalStateIndex : cl[a.getNextState().getId()].keySet()) {
            State pathFinalState = fst.getState(pathFinalStateIndex);
            pathWeight = semiring.times(
                getPathWeight(a.getNextState(), pathFinalState, cl),
                a.getWeight());
            add(state, pathFinalState, pathWeight, cl, semiring);
          }
        }
        add(state, a.getNextState(), a.getWeight(), cl, semiring);
      }
    }
  }

  /**
   * Get an epsilon path's cost in epsilon closure
   */
  private static Double getPathWeight(State in, State out,
                                      HashMap<Integer, Double>[] cl) {
    if (cl[in.getId()] != null) {
      return cl[in.getId()].get(out.getId());
    }

    return null;
  }

  /**
   * Removes epsilon transitions from an fst.
   *
   * It return a new epsilon-free fst and does not modify the original fst
   *
   * @param fst the fst to remove epsilon transitions from
   * @return the epsilon-free fst
   */
  public static MutableFst remove(Fst fst) {
    Preconditions.checkNotNull(fst);
    Preconditions.checkNotNull(fst.getSemiring());

    Semiring semiring = fst.getSemiring();
    MutableFst res = MutableFst.emptyWithCopyOfSymbols(fst);
    int iEps = fst.getInputSymbols().get(Fst.EPS);
    int oEps = fst.getOutputSymbols().get(Fst.EPS);

    @SuppressWarnings("unchecked")
    HashMap<Integer, Double>[] cl = new HashMap[fst.getStateCount()];
    MutableState[] oldToNewStateMap = new MutableState[fst.getStateCount()];
    State[] newToOldStateMap = new State[fst.getStateCount()];

    int numStates = fst.getStateCount();
    for (int i = 0; i < numStates; i++) {
      State s = fst.getState(i);
      // Add non-epsilon arcs
      MutableState newState = res.newState();
      newState.setFinalWeight(s.getFinalWeight());
      oldToNewStateMap[s.getId()] = newState;
      newToOldStateMap[newState.getId()] = s;
      if (newState.getId() == fst.getStartState().getId()) {
        res.setStart(newState);
      }
    }

    for (int i = 0; i < numStates; i++) {
      State s = fst.getState(i);
      // Add non-epsilon arcs
      MutableState newState = oldToNewStateMap[s.getId()];
      int numArcs = s.getArcCount();
      for (int j = 0; j < numArcs; j++) {
        Arc a = s.getArc(j);
        if ((a.getIlabel() != iEps) || (a.getOlabel() != oEps)) {
          MutableState resNextState = oldToNewStateMap[a.getNextState().getId()];
          res.addArc(newState, a.getIlabel(), a.getOlabel(), resNextState, a.getWeight());
        }
      }

      // Compute e-Closure
      if (cl[s.getId()] == null) {
        calcClosure(fst, s, cl, semiring, iEps, oEps);
      }
    }

    // augment fst with arcs generated from epsilon moves.
    numStates = res.getStateCount();
    for (int i = 0; i < numStates; i++) {
      MutableState s = res.getState(i);
      State oldState = newToOldStateMap[s.getId()];
      if (cl[oldState.getId()] != null) {
        for (Integer pathFinalStateIndex : cl[oldState.getId()].keySet()) {

          State s1 = fst.getState(pathFinalStateIndex);
          if (semiring.isNotZero(s1.getFinalWeight())) {
            s.setFinalWeight(semiring.plus(s.getFinalWeight(),
                                           semiring.times(getPathWeight(oldState, s1, cl),
                                                          s1.getFinalWeight())));
          }
          int numArcs = s1.getArcCount();
          for (int j = 0; j < numArcs; j++) {
            Arc a = s1.getArc(j);
            if ((a.getIlabel() != iEps) || (a.getOlabel() != oEps)) {
              double weight = semiring.times(a.getWeight(), getPathWeight(oldState, s1, cl));
              MutableState nextState = oldToNewStateMap[a.getNextState().getId()];
              res.addArc(s, a.getIlabel(), a.getOlabel(), nextState, weight);
            }
          }
        }
      }
    }

    Connect.apply(res);
    ArcSort.sortByInput(res);

    return res;
  }
}
