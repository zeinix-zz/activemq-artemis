/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.artemis.tests.integration.cluster.failover;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.activemq.artemis.api.core.ActiveMQBuffer;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.api.core.client.ClientMessage;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.api.core.client.ClusterTopologyListener;
import org.apache.activemq.artemis.api.core.client.ServerLocator;
import org.apache.activemq.artemis.api.core.client.TopologyMember;
import org.apache.activemq.artemis.core.client.impl.ClientSessionFactoryInternal;
import org.apache.activemq.artemis.core.client.impl.ServerLocatorInternal;
import org.apache.activemq.artemis.core.config.ClusterConnectionConfiguration;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.ha.DistributedPrimitiveManagerConfiguration;
import org.apache.activemq.artemis.core.config.ha.ReplicaPolicyConfiguration;
import org.apache.activemq.artemis.core.config.ha.SharedStorePrimaryPolicyConfiguration;
import org.apache.activemq.artemis.core.config.ha.SharedStoreBackupPolicyConfiguration;
import org.apache.activemq.artemis.core.remoting.impl.invm.InVMConnector;
import org.apache.activemq.artemis.core.remoting.impl.invm.InVMRegistry;
import org.apache.activemq.artemis.core.server.NodeManager;
import org.apache.activemq.artemis.core.server.cluster.ha.HAPolicy;
import org.apache.activemq.artemis.core.server.cluster.ha.ReplicatedPolicy;
import org.apache.activemq.artemis.core.server.impl.ActiveMQServerImpl;
import org.apache.activemq.artemis.core.server.impl.InVMNodeManager;
import org.apache.activemq.artemis.quorum.file.FileBasedPrimitiveManager;
import org.apache.activemq.artemis.tests.integration.cluster.util.SameProcessActiveMQServer;
import org.apache.activemq.artemis.tests.integration.cluster.util.TestableServer;
import org.apache.activemq.artemis.tests.util.ActiveMQTestBase;
import org.apache.activemq.artemis.tests.util.ReplicatedBackupUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;

public abstract class FailoverTestBase extends ActiveMQTestBase {

   protected static final SimpleString ADDRESS = new SimpleString("FailoverTestAddress");

   /*
    * Used only by tests of large messages.
    */
   protected static final int MIN_LARGE_MESSAGE = 1024;
   private static final int LARGE_MESSAGE_SIZE = MIN_LARGE_MESSAGE * 3;

   protected static final int PAGE_MAX = 2 * 1024;
   protected static final int PAGE_SIZE = 1024;


   protected TestableServer primaryServer;

   protected TestableServer backupServer;

   protected Configuration backupConfig;

   protected Configuration primaryConfig;

   protected NodeManager nodeManager;

   protected NodeManager backupNodeManager;

   protected DistributedPrimitiveManagerConfiguration managerConfiguration;

   protected boolean startBackupServer = true;

   @Override
   @Before
   public void setUp() throws Exception {
      super.setUp();
      createConfigs();

      setPrimaryIdentity();
      primaryServer.start();
      waitForServerToStart(primaryServer.getServer());

      if (backupServer != null) {
         setBackupIdentity();
         if (startBackupServer) {
            backupServer.start();
            waitForBackup();
         }
      }
   }

   protected void waitForBackup() {
      waitForRemoteBackupSynchronization(backupServer.getServer());
   }

   protected void setBackupIdentity() {
      backupServer.setIdentity(this.getClass().getSimpleName() + "/backupServers");
   }

   protected void setPrimaryIdentity() {
      primaryServer.setIdentity(this.getClass().getSimpleName() + "/primaryServer");
   }

   protected TestableServer createTestableServer(Configuration config) throws Exception {
      return createTestableServer(config, nodeManager);
   }

   protected TestableServer createTestableServer(Configuration config, NodeManager nodeManager) throws Exception {
      boolean isBackup = config.getHAPolicyConfiguration() instanceof ReplicaPolicyConfiguration || config.getHAPolicyConfiguration() instanceof SharedStoreBackupPolicyConfiguration;
      return new SameProcessActiveMQServer(createInVMFailoverServer(true, config, nodeManager, isBackup ? 2 : 1));
   }

   protected TestableServer createColocatedTestableServer(Configuration config,
                                                          NodeManager primaryNodeManager,
                                                          NodeManager backupNodeManager,
                                                          int id) {
      return new SameProcessActiveMQServer(createColocatedInVMFailoverServer(true, config, primaryNodeManager, backupNodeManager, id));
   }

   /**
    * Large message version of {@link #setBody(int, ClientMessage)}.
    *
    * @param i
    * @param message
    */
   protected static void setLargeMessageBody(final int i, final ClientMessage message) {
      try {
         message.setBodyInputStream(ActiveMQTestBase.createFakeLargeStream(LARGE_MESSAGE_SIZE));
      } catch (Exception e) {
         throw new RuntimeException(e);
      }
   }

   /**
    * Large message version of {@link #assertMessageBody(int, ClientMessage)}.
    *
    * @param i
    * @param message
    */
   protected static void assertLargeMessageBody(final int i, final ClientMessage message) {
      ActiveMQBuffer buffer = message.getBodyBuffer();

      for (int j = 0; j < LARGE_MESSAGE_SIZE; j++) {
         Assert.assertTrue("msg " + i + ", expecting " + LARGE_MESSAGE_SIZE + " bytes, got " + j, buffer.readable());
         Assert.assertEquals("equal at " + j, ActiveMQTestBase.getSamplebyte(j), buffer.readByte());
      }
   }

   /**
    * Override this if is needed a different implementation of {@link NodeManager} to be used into {@link #createConfigs()}.
    */
   protected NodeManager createNodeManager() throws Exception {
      return new InVMNodeManager(false);
   }

   protected NodeManager createNodeManager(Configuration configuration) throws Exception {
      return new InVMNodeManager(false, configuration.getNodeManagerLockLocation());
   }

   protected void createConfigs() throws Exception {
      nodeManager = createNodeManager();
      TransportConfiguration primaryConnector = getConnectorTransportConfiguration(true);
      TransportConfiguration backupConnector = getConnectorTransportConfiguration(false);

      backupConfig = super.createDefaultInVMConfig().clearAcceptorConfigurations().addAcceptorConfiguration(getAcceptorTransportConfiguration(false)).setHAPolicyConfiguration(new SharedStoreBackupPolicyConfiguration()).addConnectorConfiguration(primaryConnector.getName(), primaryConnector).addConnectorConfiguration(backupConnector.getName(), backupConnector).addClusterConfiguration(createBasicClusterConfig(backupConnector.getName(), primaryConnector.getName()));

      backupServer = createTestableServer(backupConfig);

      primaryConfig = super.createDefaultInVMConfig().clearAcceptorConfigurations().addAcceptorConfiguration(getAcceptorTransportConfiguration(true)).setHAPolicyConfiguration(new SharedStorePrimaryPolicyConfiguration()).addClusterConfiguration(createBasicClusterConfig(primaryConnector.getName())).addConnectorConfiguration(primaryConnector.getName(), primaryConnector);

      primaryServer = createTestableServer(primaryConfig);
   }

   /**
    * Override this if is needed a different implementation of {@link NodeManager} to be used into {@link #createReplicatedConfigs()}.
    */
   protected NodeManager createReplicatedBackupNodeManager(Configuration backupConfig) {
      return new InVMNodeManager(true, backupConfig.getJournalLocation());
   }

   protected boolean supportsRetention() {
      return true;
   }

   protected void createReplicatedConfigs() throws Exception {
      final TransportConfiguration primaryConnector = getConnectorTransportConfiguration(true);
      final TransportConfiguration backupConnector = getConnectorTransportConfiguration(false);
      final TransportConfiguration backupAcceptor = getAcceptorTransportConfiguration(false);

      backupConfig = createDefaultInVMConfig();
      primaryConfig = createDefaultInVMConfig();

      ReplicatedBackupUtils.configureReplicationPair(backupConfig, backupConnector, backupAcceptor, primaryConfig, primaryConnector, null);

      backupConfig.setBindingsDirectory(getBindingsDir(0, true)).setJournalDirectory(getJournalDir(0, true)).setPagingDirectory(getPageDir(0, true)).setLargeMessagesDirectory(getLargeMessagesDir(0, true)).setSecurityEnabled(false);

      setupHAPolicyConfiguration();
      backupNodeManager = createReplicatedBackupNodeManager(backupConfig);

      backupServer = createTestableServer(backupConfig, backupNodeManager);

      primaryConfig.clearAcceptorConfigurations().addAcceptorConfiguration(getAcceptorTransportConfiguration(true));

      nodeManager = createNodeManager(primaryConfig);
      primaryServer = createTestableServer(primaryConfig, nodeManager);

      if (supportsRetention()) {
         primaryServer.getServer().getConfiguration().setJournalRetentionDirectory(getJournalDir(0, false) + "_retention");
         backupServer.getServer().getConfiguration().setJournalRetentionDirectory(getJournalDir(0, true) + "_retention");
      }
   }

   protected void createPluggableReplicatedConfigs() throws Exception {
      final TransportConfiguration primaryConnector = getConnectorTransportConfiguration(true);
      final TransportConfiguration backupConnector = getConnectorTransportConfiguration(false);
      final TransportConfiguration backupAcceptor = getAcceptorTransportConfiguration(false);

      backupConfig = createDefaultInVMConfig();
      primaryConfig = createDefaultInVMConfig();

      managerConfiguration =
         new DistributedPrimitiveManagerConfiguration(FileBasedPrimitiveManager.class.getName(),
                                                      Collections.singletonMap("locks-folder", temporaryFolder.newFolder("manager").toString()));

      ReplicatedBackupUtils.configurePluggableQuorumReplicationPair(backupConfig, backupConnector, backupAcceptor, primaryConfig, primaryConnector, null, managerConfiguration, managerConfiguration);

      backupConfig.setBindingsDirectory(getBindingsDir(0, true)).setJournalDirectory(getJournalDir(0, true)).setPagingDirectory(getPageDir(0, true)).setLargeMessagesDirectory(getLargeMessagesDir(0, true)).setSecurityEnabled(false);

      setupHAPolicyConfiguration();
      backupNodeManager = createReplicatedBackupNodeManager(backupConfig);

      backupServer = createTestableServer(backupConfig, backupNodeManager);

      primaryConfig.clearAcceptorConfigurations().addAcceptorConfiguration(getAcceptorTransportConfiguration(true));

      nodeManager = createNodeManager(primaryConfig);
      primaryServer = createTestableServer(primaryConfig, nodeManager);
   }

   protected void setupHAPolicyConfiguration() {
      Assert.assertTrue(backupConfig.getHAPolicyConfiguration() instanceof ReplicaPolicyConfiguration);
      ((ReplicaPolicyConfiguration) backupConfig.getHAPolicyConfiguration()).setMaxSavedReplicatedJournalsSize(-1).setAllowFailBack(true);
      ((ReplicaPolicyConfiguration) backupConfig.getHAPolicyConfiguration()).setRestartBackup(false);
   }

   protected final void adaptPrimaryConfigForReplicatedFailBack(TestableServer server) {
      Configuration configuration = server.getServer().getConfiguration();
      final TransportConfiguration backupConnector = getConnectorTransportConfiguration(false);
      if (server.getServer().getHAPolicy().isSharedStore()) {
         ClusterConnectionConfiguration cc = configuration.getClusterConfigurations().get(0);
         Assert.assertNotNull("cluster connection configuration", cc);
         Assert.assertNotNull("static connectors", cc.getStaticConnectors());
         cc.getStaticConnectors().add(backupConnector.getName());
         // backupConnector is only necessary for fail-back tests
         configuration.getConnectorConfigurations().put(backupConnector.getName(), backupConnector);
         return;
      }
      HAPolicy policy = server.getServer().getHAPolicy();
      if (policy instanceof ReplicatedPolicy) {
         ((ReplicatedPolicy) policy).setCheckForPrimaryServer(true);
      }

   }

   @Override
   @After
   public void tearDown() throws Exception {
      logAndSystemOut("#test tearDown");

      InVMConnector.failOnCreateConnection = false;

      super.tearDown();
      Assert.assertEquals(0, InVMRegistry.instance.size());

      backupServer = null;

      primaryServer = null;

      nodeManager = null;

      backupNodeManager = null;
      try {
         ServerSocket serverSocket = new ServerSocket(61616);
         serverSocket.close();
      } catch (IOException e) {
         throw e;
      }
      try {
         ServerSocket serverSocket = new ServerSocket(61617);
         serverSocket.close();
      } catch (IOException e) {
         throw e;
      }
   }

   protected ClientSessionFactoryInternal createSessionFactoryAndWaitForTopology(ServerLocator locator,
                                                                                 int topologyMembers) throws Exception {
      CountDownLatch countDownLatch = new CountDownLatch(topologyMembers);

      locator.addClusterTopologyListener(new LatchClusterTopologyListener(countDownLatch));

      ClientSessionFactoryInternal sf = (ClientSessionFactoryInternal) locator.createSessionFactory();
      addSessionFactory(sf);

      Assert.assertTrue("topology members expected " + topologyMembers, countDownLatch.await(5, TimeUnit.SECONDS));
      return sf;
   }

   /**
    * Waits for backup to be in the "started" state and to finish synchronization with its live.
    *
    * @param sessionFactory
    * @param seconds
    * @throws Exception
    */
   protected void waitForBackup(ClientSessionFactoryInternal sessionFactory, int seconds) throws Exception {
      final ActiveMQServerImpl actualServer = (ActiveMQServerImpl) backupServer.getServer();
      if (actualServer.getHAPolicy().isSharedStore()) {
         waitForServerToStart(actualServer);
      } else {
         waitForRemoteBackup(sessionFactory, seconds, true, actualServer);
      }
   }

   protected abstract TransportConfiguration getAcceptorTransportConfiguration(boolean live);

   protected abstract TransportConfiguration getConnectorTransportConfiguration(boolean live);

   protected ServerLocatorInternal getServerLocator() throws Exception {
      return (ServerLocatorInternal) addServerLocator(ActiveMQClient.createServerLocatorWithHA(getConnectorTransportConfiguration(true), getConnectorTransportConfiguration(false))).setRetryInterval(50).setInitialConnectAttempts(50);
   }

   protected void crash(final ClientSession... sessions) throws Exception {
      this.crash(true, sessions);
   }

   protected void crash(final boolean waitFailure, final ClientSession... sessions) throws Exception {
      this.crash(true, waitFailure, sessions);
   }

   protected void crash(final boolean failover,
                        final boolean waitFailure,
                        final ClientSession... sessions) throws Exception {
      primaryServer.crash(failover, waitFailure, sessions);
   }


   public static final class LatchClusterTopologyListener implements ClusterTopologyListener {

      final CountDownLatch latch;
      List<String> primaryNode = new ArrayList<>();
      List<String> backupNode = new ArrayList<>();

      public LatchClusterTopologyListener(CountDownLatch latch) {
         this.latch = latch;
      }

      @Override
      public void nodeUP(TopologyMember topologyMember, boolean last) {
         if (topologyMember.getPrimary() != null && !primaryNode.contains(topologyMember.getPrimary().getName())) {
            primaryNode.add(topologyMember.getPrimary().getName());
            latch.countDown();
         }
         if (topologyMember.getBackup() != null && !backupNode.contains(topologyMember.getBackup().getName())) {
            backupNode.add(topologyMember.getBackup().getName());
            latch.countDown();
         }
      }

      @Override
      public void nodeDown(final long uniqueEventID, String nodeID) {
         //To change body of implemented methods use File | Settings | File Templates.
      }
   }
}
