package org.drools.reteoo;

/*
 * Copyright 2007 JBoss Inc
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
 * 
 * Created on January 8th, 2007
 */

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import org.drools.base.ShadowProxy;
import org.drools.common.BaseNode;
import org.drools.common.InternalFactHandle;
import org.drools.common.InternalWorkingMemory;
import org.drools.common.NodeMemory;
import org.drools.common.PropagationContextImpl;
import org.drools.reteoo.builder.BuildContext;
import org.drools.rule.EntryPoint;
import org.drools.spi.ObjectType;
import org.drools.spi.PropagationContext;
import org.drools.util.FactEntry;
import org.drools.util.FactHashTable;
import org.drools.util.Iterator;

/**
 * A node that is an entry point into the Rete network.
 *
 * As we move the design to support network partitions and concurrent processing 
 * of parts of the network, we also need to support multiple, independent entry
 * points and this class represents that. 
 * 
 * It replaces the function of the Rete Node class in previous designs.
 * 
 * @see ObjectTypeNode
 *
 * @author <a href="mailto:tirelli@post.com">Edson Tirelli</a>
 */
public class EntryPointNode extends ObjectSource
    implements
    Serializable,
    ObjectSink {
    // ------------------------------------------------------------
    // Instance members
    // ------------------------------------------------------------

    private static final long   serialVersionUID = 420L;

    /**
     * The entry point ID for this node
     */
    private final EntryPoint    entryPoint;

    /**
     * The object type nodes under this node
     */
    private final Map<ObjectType, ObjectTypeNode> objectTypeNodes;

    // ------------------------------------------------------------
    // Constructors
    // ------------------------------------------------------------

    public EntryPointNode(final int id,
                          final ObjectSource objectSource,
                          final BuildContext context) {
        this( id,
              objectSource,
              context.getCurrentEntryPoint() ); // irrelevant for this node, since it overrides sink management
    }

    public EntryPointNode(final int id,
                          final ObjectSource objectSource,
                          final EntryPoint entryPoint) {
        super( id,
               objectSource,
               999 ); // irrelevant for this node, since it overrides sink management
        this.entryPoint = entryPoint;
        this.objectTypeNodes = new HashMap<ObjectType, ObjectTypeNode>();
    }

    // ------------------------------------------------------------
    // Instance methods
    // ------------------------------------------------------------

    /**
     * @return the entryPoint
     */
    public EntryPoint getEntryPoint() {
        return entryPoint;
    }

    /**
     * This is the entry point into the network for all asserted Facts. Iterates a cache
     * of matching <code>ObjectTypdeNode</code>s asserting the Fact. If the cache does not
     * exist it first iterates and builds the cache.
     *
     * @param handle
     *            The FactHandle of the fact to assert
     * @param context
     *            The <code>PropagationContext</code> of the <code>WorkingMemory</code> action
     * @param workingMemory
     *            The working memory session.
     */
    public void assertObject(final InternalFactHandle handle,
                             final PropagationContext context,
                             final InternalWorkingMemory workingMemory) {

        ObjectTypeConf objectTypeConf = workingMemory.getObjectTypeConf( this.entryPoint,
                                                                         handle.getObject() );

        // checks if shadow is enabled
        if ( objectTypeConf.isShadowEnabled() ) {
            // need to improve this
            if ( !(handle.getObject() instanceof ShadowProxy) ) {
                // replaces the actual object by its shadow before propagating
                handle.setObject( objectTypeConf.getShadow( handle.getObject() ) );
                handle.setShadowFact( true );
            } else {
                ((ShadowProxy) handle.getObject()).updateProxy();
            }
        }

        ObjectTypeNode[] cachedNodes = objectTypeConf.getObjectTypeNodes();

        for ( int i = 0, length = cachedNodes.length; i < length; i++ ) {
            cachedNodes[i].assertObject( handle,
                                         context,
                                         workingMemory );
        }
    }

    /**
     * Retract a fact object from this <code>RuleBase</code> and the specified
     * <code>WorkingMemory</code>.
     *
     * @param handle
     *            The handle of the fact to retract.
     * @param workingMemory
     *            The working memory session.
     */
    public void retractObject(final InternalFactHandle handle,
                              final PropagationContext context,
                              final InternalWorkingMemory workingMemory) {
        final Object object = handle.getObject();

        ObjectTypeConf objectTypeConf = workingMemory.getObjectTypeConf( this.entryPoint,
                                                                         object );
        ObjectTypeNode[] cachedNodes = objectTypeConf.getObjectTypeNodes();

        if ( cachedNodes == null ) {
            // it is  possible that there are no ObjectTypeNodes for an  object being retracted
            return;
        }

        for ( int i = 0; i < cachedNodes.length; i++ ) {
            cachedNodes[i].retractObject( handle,
                                          context,
                                          workingMemory );
        }
    }

    /**
     * Adds the <code>ObjectSink</code> so that it may receive
     * <code>Objects</code> propagated from this <code>ObjectSource</code>.
     *
     * @param objectSink
     *            The <code>ObjectSink</code> to receive propagated
     *            <code>Objects</code>. Rete only accepts <code>ObjectTypeNode</code>s
     *            as parameters to this method, though.
     */
    protected void addObjectSink(final ObjectSink objectSink) {
        final ObjectTypeNode node = (ObjectTypeNode) objectSink;
        this.objectTypeNodes.put( node.getObjectType(),
                                  node );
    }

    protected void removeObjectSink(final ObjectSink objectSink) {
        final ObjectTypeNode node = (ObjectTypeNode) objectSink;
        this.objectTypeNodes.remove( node.getObjectType() );
    }

    public void attach() {
        this.objectSource.addObjectSink( this );
    }

    public void attach(final InternalWorkingMemory[] workingMemories) {
        attach();

        for ( int i = 0, length = workingMemories.length; i < length; i++ ) {
            final InternalWorkingMemory workingMemory = workingMemories[i];
            final PropagationContext propagationContext = new PropagationContextImpl( workingMemory.getNextPropagationIdCounter(),
                                                                                      PropagationContext.RULE_ADDITION,
                                                                                      null,
                                                                                      null );
            this.objectSource.updateSink( this,
                                          propagationContext,
                                          workingMemory );
        }
    }

    protected void doRemove(final RuleRemovalContext context,
                            final ReteooBuilder builder,
                            final BaseNode node,
                            final InternalWorkingMemory[] workingMemories) {
        final ObjectTypeNode objectTypeNode = (ObjectTypeNode) node;
        removeObjectSink( objectTypeNode );
        for ( int i = 0; i < workingMemories.length; i++ ) {
            // clear the node memory for each working memory.
            workingMemories[i].clearNodeMemory( (NodeMemory) node );
        }
    }

    public Map<ObjectType, ObjectTypeNode> getObjectTypeNodes() {
        return this.objectTypeNodes;
    }

    public int hashCode() {
        return this.entryPoint.hashCode();
    }

    public boolean equals(final Object object) {
        if ( object == this ) {
            return true;
        }

        if ( object == null || !(object instanceof EntryPointNode) ) {
            return false;
        }

        final EntryPointNode other = (EntryPointNode) object;
        return this.entryPoint.equals( other.entryPoint );
    }

    public void updateSink(final ObjectSink sink,
                           final PropagationContext context,
                           final InternalWorkingMemory workingMemory) {
        // JBRULES-612: the cache MUST be invalidated when a new node type is added to the network, so iterate and reset all caches.
        final ObjectTypeNode node = (ObjectTypeNode) sink;
        final ObjectType newObjectType = node.getObjectType();

        for ( ObjectTypeConf objectTypeConf : workingMemory.getObjectTypeConfMap( this.entryPoint ).values() ) {
            if ( newObjectType.isAssignableFrom( objectTypeConf.getConcreteObjectTypeNode().getObjectType() ) ) {
                objectTypeConf.resetCache();
                ObjectTypeNode sourceNode = objectTypeConf.getConcreteObjectTypeNode();
                FactHashTable table = (FactHashTable) workingMemory.getNodeMemory( sourceNode );
                Iterator factIter = table.iterator();
                for ( FactEntry factEntry = (FactEntry) factIter.next(); factEntry != null; factEntry = (FactEntry) factIter.next() ) {
                    sink.assertObject( factEntry.getFactHandle(),
                                       context,
                                       workingMemory );
                }
            }
        }
    }

    public boolean isObjectMemoryEnabled() {
        return false;
    }

    public void setObjectMemoryEnabled(boolean objectMemoryEnabled) {
        throw new UnsupportedOperationException( "Entry Point Node has no Object memory" );
    }
    
    public String toString() {
        return "[EntryPointNode("+this.id+") "+this.entryPoint+" ]";
    }

}