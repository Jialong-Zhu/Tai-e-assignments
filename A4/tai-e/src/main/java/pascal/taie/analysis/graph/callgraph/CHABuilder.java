/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2022 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2022 Yue Li <yueli@nju.edu.cn>
 *
 * This file is part of Tai-e.
 *
 * Tai-e is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Tai-e is distributed in the hope that it will be useful,but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Tai-e. If not, see <https://www.gnu.org/licenses/>.
 */

package pascal.taie.analysis.graph.callgraph;

import pascal.taie.World;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.classes.Subsignature;

import java.util.*;

/**
 * Implementation of the CHA algorithm.
 */
class CHABuilder implements CGBuilder<Invoke, JMethod> {

    private ClassHierarchy hierarchy;

    @Override
    public CallGraph<Invoke, JMethod> build() {
        hierarchy = World.get().getClassHierarchy();
        return buildCallGraph(World.get().getMainMethod());
    }

    private CallGraph<Invoke, JMethod> buildCallGraph(JMethod entry) {
        DefaultCallGraph callGraph = new DefaultCallGraph();
        callGraph.addEntryMethod(entry);
        Queue<JMethod> workList = new LinkedList<>();
        workList.add(entry);

        while (!workList.isEmpty()) {
            JMethod method = workList.poll();
            if (callGraph.contains(method)) continue;
            callGraph.addReachableMethod(method);

            callGraph.callSitesIn(method).forEach(callSite -> {
                for (JMethod callee : resolve(callSite)) {
                    callGraph.addEdge(new Edge<>(CallGraphs.getCallKind(callSite), callSite, callee));
                    workList.add(callee);
                }
            });
        }

        return callGraph;
    }

    /**
     * Resolves call targets (callees) of a call site via CHA.
     */
    private Set<JMethod> resolve(Invoke callSite) {
        Set<JMethod> result = new LinkedHashSet<>();
        JClass jclass = callSite.getMethodRef().getDeclaringClass();
        Subsignature subsignature = callSite.getMethodRef().getSubsignature();
        switch (CallGraphs.getCallKind(callSite)) {
            case STATIC -> result.add(jclass.getDeclaredMethod(subsignature));
            case SPECIAL -> {
                if (dispatch(jclass, subsignature) != null)
                    result.add(dispatch(jclass, subsignature));
            }
            case VIRTUAL, INTERFACE -> {
                Queue<JClass> subclasses = new LinkedList<>();
                subclasses.add(jclass);
                while (!subclasses.isEmpty()) {
                    JClass jc = subclasses.poll();
                    if (dispatch(jc, subsignature) != null)
                        result.add(dispatch(jc, subsignature));
                    if (jc.isInterface()) {
                        subclasses.addAll(hierarchy.getDirectSubinterfacesOf(jc));
                        subclasses.addAll(hierarchy.getDirectImplementorsOf(jc));
                    } else {
                        subclasses.addAll(hierarchy.getDirectSubclassesOf(jc));
                    }
                }
            }
        }

        return result;
    }

    /**
     * Looks up the target method based on given class and method subsignature.
     *
     * @return the dispatched target method, or null if no satisfying method
     * can be found.
     */
    private JMethod dispatch(JClass jclass, Subsignature subsignature) {
        JMethod method = jclass.getDeclaredMethod(subsignature);
        if (method == null){
            if (jclass.getSuperClass() != null)
                return dispatch(jclass.getSuperClass(), subsignature);
            else
                return null;
        }
        if (!method.isAbstract()) return method;
        if(jclass.getSuperClass() != null) return dispatch(jclass.getSuperClass(), subsignature);

        return null;
    }
}
