/******************************************************************
 * File:        TestStoreImpl.java
 * Created by:  Dave Reynolds
 * Created on:  30 Jan 2013
 *
 * (c) Copyright 2013, Epimorphics Limited
 *
 *****************************************************************/

package com.epimorphics.registry.store;

import static org.junit.Assert.*;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.epimorphics.rdfutil.RDFUtil;
import com.epimorphics.registry.core.Description;
import com.epimorphics.registry.core.Register;
import com.epimorphics.registry.core.RegisterItem;
import com.epimorphics.registry.core.Status;
import com.epimorphics.registry.util.Prefixes;
import com.epimorphics.registry.vocab.RegistryVocab;
import com.epimorphics.registry.vocab.Version;
import com.epimorphics.server.core.ServiceConfig;
import com.epimorphics.server.stores.MemStore;
import com.epimorphics.server.webapi.BaseEndpoint;
import com.epimorphics.util.TestUtil;
import com.epimorphics.vocabs.SKOS;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.ResourceFactory;
import com.hp.hpl.jena.util.FileUtils;
import com.hp.hpl.jena.vocabulary.DCTerms;
import com.hp.hpl.jena.vocabulary.OWL;
import com.hp.hpl.jena.vocabulary.RDF;
import com.hp.hpl.jena.vocabulary.RDFS;

public class TestStoreImpl {
    static final String BOOTSTRAP_FILE = "src/main/webapp/WEB-INF/root-register.ttl";
    static final String ROOT_REGISTER = "http://location.data.gov.uk/";
    static final String REG1 = ROOT_REGISTER + "reg1";

    MemStore basestore;
    StoreAPI store;

    @Before
    public void setup() {
        basestore = new MemStore();
        basestore.init(new HashMap<String, String>(), null);

        Map<String, String> config = new HashMap<String, String>();
        config.put( StoreBaseImpl.STORE_PARAMETER, "basestore");
        StoreBaseImpl store = new StoreBaseImpl();
        store.init(config, null);

        ServiceConfig.get().initServices("basestore", basestore, "store", store);
        this.store = store;
        store.loadBootstrap(BOOTSTRAP_FILE);
    }

    @Test
    public void testBaseLoad() {
        Description rootreg = store.getCurrentVersion(ROOT_REGISTER, false);
        assertTrue(rootreg instanceof Register);
        Resource r = rootreg.getRoot();
        assertTrue(r.hasProperty(RDF.type, RegistryVocab.Register));
        assertEquals("root", RDFUtil.getStringValue(r, RDFS.label));
        assertFalse(r.hasProperty(Version.currentVersion));
    }

    @Test
    public void testMetadataUpdate() {
        Register rootreg = (Register)store.getCurrentVersion(ROOT_REGISTER, true);
        rootreg.setProperty(RDFS.label, ResourceFactory.createPlainLiteral("new root"));
        store.update(rootreg);

        Resource updatedroot = store.getDescription(ROOT_REGISTER, false).getRoot();
        assertTrue( TestUtil.isOnlyValue(updatedroot, Version.currentVersion, null) );

        Register updatedreg = store.getCurrentVersion(ROOT_REGISTER, false).asRegister();
        assertEquals("new root", RDFUtil.getStringValue(updatedreg.getRoot(), RDFS.label));

        List<VersionInfo> versions = store.listVersions(ROOT_REGISTER);
        assertEquals(2, versions.size());
        VersionInfo vi1 = versions.get(0);
        VersionInfo vi2 = versions.get(1);
        assertEquals("1", vi1.getVersion());
        assertEquals("2", vi2.getVersion());
        assertEquals(vi1.getToTime(), vi2.getFromTime());
        assertNotSame(-1, vi1.getToTime());

        String versionURI = vi1.getUri();
        Register oldreg = store.getVersion(versionURI).asRegister();
        assertEquals("root", RDFUtil.getStringValue(oldreg.getRoot(), RDFS.label));
    }

    @Test
    public void testRegisterCreate() {
        Register rootreg = store.getCurrentVersion(ROOT_REGISTER, true).asRegister();
        addEntry("file:test/reg1.ttl", ROOT_REGISTER);

        // Check the item pointing to the new subregister
        String expectedItemURI = ROOT_REGISTER + "_reg1";
        RegisterItem ri = store.getCurrentVersion(expectedItemURI, false).asRegisterItem();
        assertEquals("register 1", RDFUtil.getStringValue(ri.getRoot(), RDFS.label));
        assertEquals("Example register 1", RDFUtil.getStringValue(ri.getRoot(), DCTerms.description));
        assertTrue(TestUtil.isOnlyValue(ri.getRoot(), DCTerms.dateSubmitted, null));
        assertTrue(TestUtil.isOnlyValue(ri.getRoot(), RegistryVocab.status, RegistryVocab.statusSubmitted));
        assertTrue( ri.getRoot().hasProperty(RDF.type, RegistryVocab.RegisterItem));

        // Check the subregister itself
        Resource subregE = store.getEntity(ri);
        assertTrue (subregE.hasProperty(RDF.type, RegistryVocab.Register));
        assertTrue (subregE.hasProperty(RegistryVocab.containedItemClass, SKOS.Concept));

        // Check the root register shows this
        List<RegisterEntryInfo> members = store.listMembers(rootreg);
        assertEquals(1, members.size());
        RegisterEntryInfo entry = members.get(0);
        assertEquals(ROOT_REGISTER + "reg1", entry.getEntityURI());
        assertEquals("reg1", entry.getNotation());
        assertEquals(Status.Submitted, entry.getStatus());
        TestUtil.testArray(entry.getTypes().toArray(), new Object[]{ RegistryVocab.Register, SKOS.Collection});

        assertTrue( store.contains(rootreg, "reg1") );
        assertFalse( store.contains(rootreg, "reg2") );
    }

    private void addEntry(String defFile, String parentURI) {
        Register parent = store.getCurrentVersion(parentURI, true).asRegister();
        Model subregM = ModelFactory.createDefaultModel();
        subregM.read(defFile, BaseEndpoint.DUMMY_BASE_URI, FileUtils.langTurtle);
        Resource subregR = RDFUtil.findRoot( subregM );

        RegisterItem subregItem = RegisterItem.fromEntityRequest(subregR, parentURI, true);

        store.addToRegister(parent, subregItem);
        store.unlock(parentURI);
    }

    @Test
    public void testEntryManagement() {
        addEntry("file:test/reg1.ttl", ROOT_REGISTER);
        addEntry("file:test/blue.ttl", REG1);
        addEntry("file:test/red.ttl", REG1);

        Register reg1 = store.getCurrentVersion(REG1, false).asRegister();
        assertEquals(2, store.listMembers(reg1).size());

        RegisterItem ri = store.getItem(ROOT_REGISTER + "reg1/_red", true, false);
        checkItemWithEntity(ri, "red");

        assertNull(store.getItem(ROOT_REGISTER + "reg1/_red", false, false).getEntity());

        List<RegisterItem> members = store.fetchMembers(reg1, true);
        assertEquals(2, members.size());
        if (members.get(0).getRoot().getURI().endsWith("red")) {
            checkItemWithEntity(members.get(0), "red");
            checkItemWithEntity(members.get(1), "blue");
        } else {
            checkItemWithEntity(members.get(1), "red");
            checkItemWithEntity(members.get(0), "blue");
        }

        // Update item metadata
        ri = store.getItem(ROOT_REGISTER + "reg1/_red", false, true);
        ri.setProperty(RegistryVocab.status, RegistryVocab.statusAccepted);
        store.update(ri, false);

        ri = store.getItem(ROOT_REGISTER + "reg1/_red", true, false);
        assertTrue(ri.getRoot().hasProperty(RegistryVocab.status, RegistryVocab.statusAccepted));
        assertEquals("red", RDFUtil.getStringValue(ri.getRoot(), RDFS.label));
        assertEquals(2, RDFUtil.getIntValue(ri.getRoot(), OWL.versionInfo, -1));

        // Update item content
        long ts = doUpdate(ROOT_REGISTER + "reg1/_red", "reddish");

        ri = store.getItem(ROOT_REGISTER + "reg1/_red", true, false);
        assertTrue(ri.getRoot().hasProperty(RegistryVocab.status, RegistryVocab.statusAccepted));
        assertEquals("reddish", RDFUtil.getStringValue(ri.getRoot(), RDFS.label));
        assertEquals("reddish", RDFUtil.getStringValue(ri.getEntity(), RDFS.label));
        assertEquals(ts, RDFUtil.asTimestamp( ri.getRoot().getProperty(DCTerms.modified).getObject() ) );

        assertEquals(3, RDFUtil.getIntValue(ri.getRoot(), OWL.versionInfo, -1));

    }

    @Test
    public void testVersionRetrieval() {
        addEntry("file:test/reg1.ttl", ROOT_REGISTER);
        long ts0 = Calendar.getInstance().getTimeInMillis();
        addEntry("file:test/red.ttl", REG1);
        String itemURI = ROOT_REGISTER + "reg1/_red";
        long ts1 = doUpdate(itemURI, "red1");
        long ts2 = doUpdate(itemURI, "red2");
        long ts3 = doUpdate(itemURI, "red3");
        checkVersionAt(itemURI, ts1-1, "red");
        checkVersionAt(itemURI, ts2-1, "red1");
        checkVersionAt(itemURI, ts3-1, "red2");
        checkVersionAt(itemURI, ts3+1, "red3");
        checkVersionAt(itemURI, ts0, null);
        
        RegisterItem ri = store.getVersion(ROOT_REGISTER + "reg1/_red:3").asRegisterItem();
        assertEquals("red2", RDFUtil.getStringValue(ri.getRoot(), RDFS.label));
    }

    private long doUpdate(String item, String label) {
        try {
            Thread.sleep(10, 0);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        RegisterItem ri = store.getItem(item, true, true);
        Resource e = ri.getEntity();
        e.removeAll(RDFS.label).addProperty(RDFS.label, label);
        Calendar now = Calendar.getInstance();
        ri.updateForEntity(false, now);
        store.update(ri, true);
        return now.getTimeInMillis();
    }

    private void checkVersionAt(String uri, long ts, String label) {
        Description d = store.getVersionAt(uri, ts);
        if (label == null) {
            assertNull(d);
        } else {
            assertNotNull(d);
            RegisterItem item = d.asRegisterItem();
            assertEquals(label, RDFUtil.getStringValue(item.getRoot(), RDFS.label));
            Resource entity = store.getEntity(item);
            assertEquals(label, RDFUtil.getStringValue(entity, RDFS.label));
        }
    }

    private void checkItemWithEntity(RegisterItem ri, String colour) {
        assertTrue(ri.getRoot().hasProperty(RegistryVocab.status, RegistryVocab.statusSubmitted));
        assertEquals(colour, RDFUtil.getStringValue(ri.getRoot(), RDFS.label));
        assertTrue(ri.getRoot().hasProperty(RegistryVocab.itemClass, SKOS.Concept));

        Resource entity = ri.getEntity();
        assertEquals(ROOT_REGISTER + "reg1/" + colour, entity.getURI());
        assertEquals(colour, RDFUtil.getStringValue(entity, RDFS.label));
        assertTrue(entity.hasProperty(RDF.type, SKOS.Concept));
    }

    // TODO test getVersionAt

    @SuppressWarnings("unused")
    private void dumpAll() {
        dumpAll( basestore.asDataset().getDefaultModel() );
    }

    private void dumpAll(Model m) {
        m.setNsPrefixes( Prefixes.get() );
        m.write(System.out, FileUtils.langTurtle);
    }
}