// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

import com.intellij.ide.IdeEventQueue;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.TransactionGuard;
import com.intellij.openapi.extensions.ExtensionPoint;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.extensions.ExtensionsArea;
import com.intellij.openapi.extensions.PluginDescriptor;
import com.intellij.openapi.extensions.impl.ExtensionPointImpl;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.ShutDownTracker;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.rt.execution.junit.MapSerializerUtil;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.testFramework.LightPlatformTestCase;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.util.SystemProperties;
import com.intellij.util.containers.ContainerUtil;
import junit.framework.TestCase;
import org.jetbrains.annotations.NotNull;
import org.junit.Assume;
import org.junit.FixMethodOrder;
import org.junit.runners.MethodSorters;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * This must be the last test.
 *
 * @author max
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@SuppressWarnings({"JUnitTestClassNamingConvention", "UseOfSystemOutOrSystemErr"})
public class _LastInSuiteTest extends TestCase {
  private static final Set<String> EXTENSION_POINTS_WHITE_LIST = Collections.emptySet();

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    Disposer.setDebugMode(true);
  }

  @Override
  public String getName() {
    String name = super.getName();
    String buildConf = System.getProperty("teamcity.buildConfName");
    return buildConf == null ? name : name + "[" + buildConf + "]";
  }

  @SuppressWarnings("CallToSystemGC")
  public void testDynamicExtensions() {
    Assume.assumeTrue(!EXTENSION_POINTS_WHITE_LIST.isEmpty() ||
                      SystemProperties.getBooleanProperty("intellij.test.all.dynamic.extension.points", false));

    Map<ExtensionPoint<?>, Collection<WeakReference<Object>>> extensions = collectDynamicNonPlatformExtensions();
    unloadExtensionPoints(extensions.keySet());

    System.gc();
    System.gc();

    AtomicBoolean failed = new AtomicBoolean(false);
    extensions.forEach((ep, references) -> {
      String testName = escape("Dynamic EP unloading " + ep.getName());
      System.out.printf("##teamcity[testStarted name='%s']%n", testName);
      System.out.flush();

      List<Object> alive = ContainerUtil.mapNotNull(references, WeakReference::get);
      if (!alive.isEmpty()) {
        String aliveExtensions = StringUtil.join(alive, o -> o.getClass().getName(), "\n");
        System.out.printf("##teamcity[%s name='%s' message='%s']%n", MapSerializerUtil.TEST_FAILED, testName, 
                          escape("Not unloaded extensions:\n" + aliveExtensions + "\n\n" + "See testDynamicExtensions output to find a heapDump"));
        System.out.flush();
        failed.set(true);
      }
      else {
        System.out.printf("##teamcity[testFinished name='%s']%n", testName);
        System.out.flush();
      }
    });

    if (failed.get()) {
      String heapDump = HeavyPlatformTestCase.publishHeapDump("dynamicExtension");
      fail("Some of dynamic extensions have not been unloaded. See individual tests for details. Heap dump: " + heapDump);
    }
  }

  @NotNull
  private static String escape(String s) {
    return MapSerializerUtil.escapeStr(s, MapSerializerUtil.STD_ESCAPER);
  }

  private static void unloadExtensionPoints(@NotNull Set<ExtensionPoint<?>> extensionPoints) {
    for (ExtensionPoint<?> ep : extensionPoints) {
      ApplicationManager.getApplication().invokeAndWait(() -> {
        ep.unregisterExtensions((a, b) -> false, false);
      });
    }
  }

  @NotNull
  private static Map<ExtensionPoint<?>, Collection<WeakReference<Object>>> collectDynamicNonPlatformExtensions() {
    Map<ExtensionPoint<?>, Collection<WeakReference<Object>>> extensions = new HashMap<>();
    boolean useWhiteList = !SystemProperties.getBooleanProperty("intellij.test.all.dynamic.extension.points", false);
    ExtensionsArea area = Extensions.getRootArea();
    if (area == null) {
      return extensions;
    }
    for (ExtensionPoint<?> ep : area.getExtensionPoints()) {
      if (!ep.isDynamic()) continue;
      if (useWhiteList && !EXTENSION_POINTS_WHITE_LIST.contains(ep.getName())) continue;

      extensions.put(ep, ep.extensions()
        .filter(e -> !isPlatformExtension(ep, e))
        .map(WeakReference<Object>::new)
        .collect(Collectors.toList()));
    }
    return extensions;
  }

  private static boolean isPlatformExtension(ExtensionPoint<?> ep, Object extension) {
    //noinspection unchecked
    PluginDescriptor plugin = ((ExtensionPointImpl<Object>)ep).getPluginDescriptor(extension);
    return plugin != null && plugin.getPluginId() == PluginManagerCore.CORE_ID;
  }

  public void testProjectLeak() {
    if (Boolean.getBoolean("idea.test.guimode")) {
      Application application = ApplicationManager.getApplication();
      TransactionGuard.getInstance().submitTransactionAndWait(() -> {
        IdeEventQueue.getInstance().flushQueue();
        application.exit(true, true, false);
      });
      ShutDownTracker.getInstance().waitFor(100, TimeUnit.SECONDS);
      return;
    }

    PlatformTestUtil.disposeApplicationAndCheckForProjectLeaks();

    try {
      Disposer.assertIsEmpty(true);
    }
    catch (AssertionError | Exception e) {
      PlatformTestUtil.captureMemorySnapshot();
      throw e;
    }
  }

  public void testStatistics() {
    long started = _FirstInSuiteTest.getSuiteStartTime();
    if (started != 0) {
      long testSuiteDuration = System.nanoTime() - started;
      System.out.println(String.format("##teamcity[buildStatisticValue key='ideaTests.totalTimeMs' value='%d']", testSuiteDuration / 1000000));
    }
    LightPlatformTestCase.reportTestExecutionStatistics();
  }
}