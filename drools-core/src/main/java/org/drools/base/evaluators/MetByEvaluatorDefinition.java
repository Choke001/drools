/*
 * Copyright 2010 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.drools.base.evaluators;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.drools.RuntimeDroolsException;
import org.drools.base.BaseEvaluator;
import org.drools.base.ValueType;
import org.drools.common.EventFactHandle;
import org.drools.common.InternalFactHandle;
import org.drools.common.InternalWorkingMemory;
import org.drools.rule.VariableRestriction.ObjectVariableContextEntry;
import org.drools.rule.VariableRestriction.VariableContextEntry;
import org.drools.spi.Evaluator;
import org.drools.spi.FieldValue;
import org.drools.spi.InternalReadAccessor;
import org.drools.time.Interval;

/**
 * <p>The implementation of the <code>metby</code> evaluator definition.</p>
 * 
 * <p>The <b><code>metby</code></b> evaluator correlates two events and matches when the current event's 
 * start timestamp happens at the same time as the correlated event's end timestamp.</p> 
 * 
 * <p>Lets look at an example:</p>
 * 
 * <pre>$eventA : EventA( this metby $eventB )</pre>
 *
 * <p>The previous pattern will match if and only if the $eventA starts at the same time $eventB finishes. 
 * In other words:</p>
 * 
 * <pre> 
 * abs( $eventA.startTimestamp - $eventB.endTimestamp ) == 0
 * </pre>
 * 
 * <p>The <b><code>metby</code></b> evaluator accepts one optional parameter. If it is defined, it determines
 * the maximum distance between the end timestamp of the correlated event and the start timestamp of the current
 * event in order for the operator to match. Example:</p>
 * 
 * <pre>$eventA : EventA( this metby[ 5s ] $eventB )</pre>
 * 
 * Will match if and only if:
 * 
 * <pre> 
 * abs( $eventA.startTimestamp - $eventB.endTimestamp) <= 5s 
 * </pre>
 * 
 * <p><b>NOTE:</b> it makes no sense to use a negative interval value for the parameter and the 
 * engine will raise an exception if that happens.</p>
 */
public class MetByEvaluatorDefinition
    implements
    EvaluatorDefinition {

    public static final Operator        MET_BY        = Operator.addOperatorToRegistry( "metby",
                                                                                        false );
    public static final Operator        NOT_MET_BY    = Operator.addOperatorToRegistry( "metby",
                                                                                        true );

    private static final String[]       SUPPORTED_IDS = {MET_BY.getOperatorString()};

    private Map<String, MetByEvaluator> cache         = Collections.emptyMap();
    private volatile TimeIntervalParser parser        = new TimeIntervalParser();

    @SuppressWarnings("unchecked")
    public void readExternal(ObjectInput in) throws IOException,
                                            ClassNotFoundException {
        cache = (Map<String, MetByEvaluator>) in.readObject();
    }

    public void writeExternal(ObjectOutput out) throws IOException {
        out.writeObject( cache );
    }

    /**
     * @inheridDoc
     */
    public Evaluator getEvaluator(ValueType type,
                                  Operator operator) {
        return this.getEvaluator( type,
                                  operator.getOperatorString(),
                                  operator.isNegated(),
                                  null );
    }

    /**
     * @inheridDoc
     */
    public Evaluator getEvaluator(ValueType type,
                                  Operator operator,
                                  String parameterText) {
        return this.getEvaluator( type,
                                  operator.getOperatorString(),
                                  operator.isNegated(),
                                  parameterText );
    }

    /**
     * @inheritDoc
     */
    public Evaluator getEvaluator(final ValueType type,
                                  final String operatorId,
                                  final boolean isNegated,
                                  final String parameterText) {
        return this.getEvaluator( type,
                                  operatorId,
                                  isNegated,
                                  parameterText,
                                  Target.HANDLE,
                                  Target.HANDLE );
        
    }
    
    /**
     * @inheritDoc
     */
    public Evaluator getEvaluator(final ValueType type,
                                  final String operatorId,
                                  final boolean isNegated,
                                  final String parameterText,
                                  final Target left,
                                  final Target right ) {
        if ( this.cache == Collections.EMPTY_MAP ) {
            this.cache = new HashMap<String, MetByEvaluator>();
        }
        String key = isNegated + ":" + parameterText;
        MetByEvaluator eval = this.cache.get( key );
        if ( eval == null ) {
            Long[] params = parser.parse( parameterText );
            eval = new MetByEvaluator( type,
                                       isNegated,
                                       params,
                                       parameterText );
            this.cache.put( key,
                            eval );
        }
        return eval;
    }

    /**
     * @inheritDoc
     */
    public String[] getEvaluatorIds() {
        return SUPPORTED_IDS;
    }

    /**
     * @inheritDoc
     */
    public boolean isNegatable() {
        return true;
    }

    /**
     * @inheritDoc
     */
    public Target getTarget() {
        return Target.HANDLE;
    }

    /**
     * @inheritDoc
     */
    public boolean supportsType(ValueType type) {
        // supports all types, since it operates over fact handles
        // Note: should we change this interface to allow checking of event classes only?
        return true;
    }

    /**
     * Implements the 'metby' evaluator itself
     */
    public static class MetByEvaluator extends BaseEvaluator {
        private static final long serialVersionUID = 510l;

        private long              finalRange;
        private String            paramText;

        public MetByEvaluator() {
        }

        public MetByEvaluator(final ValueType type,
                              final boolean isNegated,
                              final Long[] parameters,
                              final String paramText) {
            super( type,
                   isNegated ? NOT_MET_BY : MET_BY );
            this.paramText = paramText;
            this.setParameters( parameters );
        }

        public void readExternal(ObjectInput in) throws IOException,
                                                ClassNotFoundException {
            super.readExternal( in );
            finalRange = in.readLong();
            paramText = (String) in.readObject();
        }

        public void writeExternal(ObjectOutput out) throws IOException {
            super.writeExternal( out );
            out.writeLong( finalRange );
            out.writeObject( paramText );
        }

        @Override
        public Object prepareLeftObject(InternalFactHandle handle) {
            return handle;
        }

        @Override
        public boolean isTemporal() {
            return true;
        }

        @Override
        public Interval getInterval() {
            if ( this.getOperator().isNegated() ) {
                return new Interval( Interval.MIN,
                                     Interval.MAX );
            }
            return new Interval( Interval.MIN,
                                 0 );
        }

        public boolean evaluate(InternalWorkingMemory workingMemory,
                                final InternalReadAccessor extractor,
                                final Object object1,
                                final FieldValue object2) {
            throw new RuntimeDroolsException( "The 'metby' operator can only be used to compare one event to another, and never to compare to literal constraints." );
        }

        public boolean evaluateCachedRight(InternalWorkingMemory workingMemory,
                                           final VariableContextEntry context,
                                           final Object left) {
            if ( context.rightNull ) {
                return false;
            }
            long rightStartTS = ((EventFactHandle) ((ObjectVariableContextEntry) context).right).getStartTimestamp();
            long dist = Math.abs( rightStartTS - ((EventFactHandle) left).getEndTimestamp() );
            return this.getOperator().isNegated() ^ ( dist <= this.finalRange );
        }

        public boolean evaluateCachedLeft(InternalWorkingMemory workingMemory,
                                          final VariableContextEntry context,
                                          final Object right) {
            if ( context.extractor.isNullValue( workingMemory,
                                                right ) ) {
                return false;
            }
            long rightStartTS = ((EventFactHandle) right).getStartTimestamp();
            long dist = Math.abs( rightStartTS - ((EventFactHandle) ((ObjectVariableContextEntry) context).left).getEndTimestamp() );

            return this.getOperator().isNegated() ^ ( dist <= this.finalRange );
        }

        public boolean evaluate(InternalWorkingMemory workingMemory,
                                final InternalReadAccessor extractor1,
                                final Object object1,
                                final InternalReadAccessor extractor2,
                                final Object object2) {
            if ( extractor1.isNullValue( workingMemory,
                                         object1 ) ) {
                return false;
            }
            long obj1StartTS = ((EventFactHandle) object1).getStartTimestamp();
            long dist = Math.abs( obj1StartTS - ((EventFactHandle) object2).getEndTimestamp() );
            return this.getOperator().isNegated() ^ ( dist <= this.finalRange );
        }

        public String toString() {
            return "metby[" + ((paramText != null) ? paramText : "") + "]";
        }

        /* (non-Javadoc)
         * @see java.lang.Object#hashCode()
         */
        @Override
        public int hashCode() {
            final int PRIME = 31;
            int result = super.hashCode();
            result = PRIME * result + (int) (finalRange ^ (finalRange >>> 32));
            return result;
        }

        /* (non-Javadoc)
         * @see java.lang.Object#equals(java.lang.Object)
         */
        @Override
        public boolean equals(Object obj) {
            if ( this == obj ) return true;
            if ( !super.equals( obj ) ) return false;
            if ( getClass() != obj.getClass() ) return false;
            final MetByEvaluator other = (MetByEvaluator) obj;
            return finalRange == other.finalRange;
        }

        /**
         * This methods sets the parameters appropriately.
         *
         * @param parameters
         */
        private void setParameters(Long[] parameters) {
            if ( parameters == null || parameters.length == 0 ) {
                this.finalRange = 0;
            } else if ( parameters.length == 1 ) {
                if ( parameters[0].longValue() >= 0 ) {
                    // defined max distance
                    this.finalRange = parameters[0].longValue();
                } else {
                    throw new RuntimeDroolsException( "[MetBy Evaluator]: Not possible to use negative parameter: '" + paramText + "'" );
                }
            } else {
                throw new RuntimeDroolsException( "[MetBy Evaluator]: Not possible to use " + parameters.length + " parameters: '" + paramText + "'" );
            }
        }

    }

}
