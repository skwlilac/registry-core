/******************************************************************
 * File:        CommandRead.java
 * Created by:  Dave Reynolds
 * Created on:  22 Jan 2013
 *
 * (c) Copyright 2013, Epimorphics Limited
 *
 *****************************************************************/

package com.epimorphics.registry.commands;

import static com.epimorphics.registry.webapi.Parameters.COLLECTION_METADATA_ONLY;
import static com.epimorphics.registry.webapi.Parameters.ENTITY_LOOKUP;
import static com.epimorphics.registry.webapi.Parameters.STATUS;
import static com.epimorphics.registry.webapi.Parameters.VERSION_AT;
import static com.epimorphics.registry.webapi.Parameters.VIEW;
import static com.epimorphics.registry.webapi.Parameters.WITH_METADATA;
import static com.epimorphics.registry.webapi.Parameters.WITH_VERSION;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import com.epimorphics.registry.core.Command;
import com.epimorphics.registry.core.Description;
import com.epimorphics.registry.core.Register;
import com.epimorphics.registry.core.RegisterItem;
import com.epimorphics.registry.core.Registry;
import com.epimorphics.registry.core.Status;
import com.epimorphics.registry.store.EntityInfo;
import com.epimorphics.registry.util.Util;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.sun.jersey.api.NotFoundException;


public class CommandRead extends Command {

    boolean withVersion;
    boolean withMetadata;
    boolean versioned;
    boolean timestamped;
    boolean entityLookup;
    public CommandRead(Operation operation, String target,
            MultivaluedMap<String, String> parameters, Registry registry) {
        super(operation, target, parameters, registry);
        withVersion = hasParamValue(VIEW, WITH_VERSION);
        withMetadata = hasParamValue(VIEW, WITH_METADATA);
        versioned = lastSegment.contains(":");
        timestamped = parameters.containsKey(VERSION_AT);
        entityLookup = parameters.containsKey(ENTITY_LOOKUP);
    }

    @Override
    public Response doExecute() {
        if (entityLookup) {
            return entityLookup();
        }
        Description d = null;
        boolean entityWithMetadata = false;
        if (lastSegment.startsWith("_")) {
            // An item
            if (versioned) {
                // An explicit item version
                d = store.getVersion(target);
                if (d != null) {
                    store.getEntity(d.asRegisterItem());
                }
            } else {
                //  plain item
                if (withVersion) {
                    d = store.getItemWithVersion(target, true);
                } else {
                    d = store.getItem(target, true) ;
                }
            }
        } else {
            // An entity
            if (versioned) {
                // This case only arises for registers
                d = store.getVersion(target);
            } else  if ( withMetadata ) {
                // Entity with metadata
                entityWithMetadata = true;
                d = store.getItem(parent +"/_" + lastSegment, true);
            } else {
                // plain entity
                d = store.getCurrentVersion(target);
            }
        }

        if (d == null) {
            throw new NotFoundException();
        }

        Model m = d.getRoot().getModel();
        // Include any entity in the response
        if (d instanceof RegisterItem) {
            RegisterItem ri = d.asRegisterItem();
            if (ri.getEntity() != null) {
                m.add( ri.getEntity().getModel() );
            }
        }
        if (entityWithMetadata) {
            d = Description.descriptionFrom(d.asRegisterItem().getEntity(), store);
        }
        if (d instanceof Register) {
            m = registerRead(d.asRegister());
        }

        return returnModel(m, d.getRoot().getURI() );
    }

    private Response entityLookup() {
        Model result = ModelFactory.createDefaultModel();
        String uri = parameters.getFirst(ENTITY_LOOKUP);
        Status statusFilter = Status.forString(parameters.getFirst(STATUS), Status.Any);
        for (EntityInfo entityInfo : store.listEntityOccurences(uri)) {
            if (entityInfo.getStatus().isA(statusFilter)) {
                if (entityInfo.getRegisterURI().startsWith(target)) {
                    if (withMetadata) {
                        RegisterItem ri = store.getItem(entityInfo.getItemURI(), true);
                        result.add( ri.getRoot().getModel() );
                        result.add( ri.getEntity().getModel() );
                    } else {
                        Description d = store.getCurrentVersion(entityInfo.getEntityURI());
                        result.add(d.getRoot().getModel());
                    }
                }
            }
        }
        return returnModel(result, target);
    }

    Model registerRead(Register register) {
        if (parameters.containsKey(COLLECTION_METADATA_ONLY)) {
            return register.getRoot().getModel();
        } else {
            // Status filter option
            Status status = Status.forString( parameters.getFirst(STATUS), Status.Accepted );

            // Select version of view
            long timestamp = -1;
            if (versioned) {
                timestamp = store.versionStartedAt(target);
            } else {
                timestamp = Util.asTimestamp( parameters.getFirst(VERSION_AT) );
            }
            Model view = ModelFactory.createDefaultModel();
            boolean complete = register.constructView(view, withVersion, withMetadata, status, pagenum * length, length, timestamp);

            // Paging parameters
            if (paged) {
                injectPagingInformation(view, register.getRoot(), !complete);
            }

            return view;
        }
    }

}
