package es.us.isa.restest.runners;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import es.us.isa.restest.specification.OpenAPISpecification;
import es.us.isa.restest.util.*;
import es.us.isa.restest.util.ClassLoader;
import es.us.isa.restest.writers.restassured.RESTAssuredWriter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;

import es.us.isa.restest.generators.AbstractTestCaseGenerator;
import es.us.isa.restest.reporting.AllureReportManager;
import es.us.isa.restest.reporting.StatsReportManager;
import es.us.isa.restest.testcases.TestCase;
import es.us.isa.restest.writers.IWriter;

import static es.us.isa.restest.util.Timer.TestStep.*;

/**
 * This class implements a basic test workflow: test generation -> test writing -> class compilation and loading -> test execution -> test report generation -> test coverage report generation.
 * @author Sergio Segura
 *
 */
public class RESTestWorkflow {

	protected String targetDir;							// Directory where tests will be generated
	protected String testClassName;						// Name of the class to be generated
	private String testId="";
	private String packageName;							// Package name
	private AbstractTestCaseGenerator generator;   		// Test case generator
	protected IWriter writer;							// RESTAssured writer
	protected AllureReportManager allureReportManager;	// Allure report manager
	protected StatsReportManager statsReportManager;	// Stats report manager
	private boolean executeTestCases = true;			// Whether to execute test cases
	private boolean allureReports = true;				// Whether to actually generate reports or not (folder "allure-reports")
	private int numTestCases = 0;						// Number of test cases generated so far

	private OpenAPISpecification spec;
	private String confPath;

	private static final Logger logger = LogManager.getLogger(RESTestWorkflow.class.getName());

	public RESTestWorkflow(String testClassName, String targetDir, String packageName, OpenAPISpecification spec, String confPath, AbstractTestCaseGenerator generator, IWriter writer, AllureReportManager reportManager, StatsReportManager statsReportManager) {
		this.targetDir = targetDir;
		this.packageName = packageName;
		this.testClassName = testClassName;
		this.generator = generator;
		this.writer = writer;
		this.allureReportManager = reportManager;
		this.statsReportManager = statsReportManager;

		this.spec = spec;
		this.confPath = confPath;

	}
	  
	public void run() throws RESTestException {

		// Test generation and writing (RESTAssured)
		testGeneration();

		if(executeTestCases) {
			// Test execution in a separate process to avoid classloader issues with Groovy/RestAssured
			logger.info("Running tests in separate process");
			testExecutionInSeparateProcess();
		}

		generateReports();

	}

	protected void generateReports() {
		if(executeTestCases && allureReports) {
			// Generate test report
			logger.info("Generating test report");
			allureReportManager.generateReport();
			
			// Detect injected faults from test results
			logger.info("Analyzing test results for injected faults");
			String injectedFaultsFile = PropertyManager.readProperty("injected.faults.file");
			es.us.isa.restest.reporting.FaultDetector faultDetector;
			if (injectedFaultsFile != null && !injectedFaultsFile.isEmpty()) {
				faultDetector = new es.us.isa.restest.reporting.FaultDetector(
					allureReportManager.getResultsDirPath(),
					"target/fault-detection-logs",
					injectedFaultsFile
				);
			} else {
				faultDetector = new es.us.isa.restest.reporting.FaultDetector(
					allureReportManager.getResultsDirPath(),
					"target/fault-detection-logs"
				);
			}
			faultDetector.analyzeResults();
			faultDetector.generateReport();
		}

		// Generate coverage report
		logger.info("Generating CSV data");
		statsReportManager.generateReport(testId, executeTestCases);
	}

	protected Class<?> getTestClass() {
		// Load test class
		String filePath = targetDir + "/" + testClassName + ".java";
		String className = packageName + "." + testClassName;
		logger.info("Compiling and loading test class {}.java", className);
		return ClassLoader.loadClass(filePath, className);
	}

	private void testGeneration() throws RESTestException {
	    
		// Generate test cases
		logger.info("Generating tests");
		Timer.startCounting(TEST_SUITE_GENERATION);
		Collection<TestCase> testCases = generator.generate();
		Timer.stopCounting(TEST_SUITE_GENERATION);
        this.numTestCases += testCases.size();

        // Pass test cases to the statistic report manager (CSV writing, coverage)
        statsReportManager.setTestCases(testCases);
        
        // Write test cases
        String filePath = targetDir + "/" + testClassName + ".java";
        logger.info("Writing {} test cases to test class {}", testCases.size(), filePath);
        writer.write(testCases);

	}

	/**
	 * Execute tests in a separate process using JUnitCore directly.
	 * This compiles and runs the test class in an isolated process.
	 */
	protected void testExecutionInSeparateProcess() {
		String className = packageName + "." + testClassName;
		
		// Calculate the source root (go up from package directory)
		File sourceDir = new File(targetDir);
		int packageDepth = packageName.split("\\.").length;
		File sourceRoot = sourceDir;
		for (int i = 0; i < packageDepth; i++) {
			sourceRoot = sourceRoot.getParentFile();
		}
		
		String allureResultsDir = allureReportManager.getResultsDirPath();
		
		try {
			// Build classpath from current classloader (more reliable than system property)
			String classpath = buildFullClasspath();
			logger.debug("Built classpath with {} chars", classpath.length());
			
			// Find all Java files in the target directory (handles multi-class test suites)
			List<String> sourceFiles = new ArrayList<>();
			File[] javaFiles = sourceDir.listFiles((dir, name) -> 
				name.startsWith(testClassName) && name.endsWith(".java"));
			if (javaFiles != null) {
				for (File javaFile : javaFiles) {
					sourceFiles.add(javaFile.getAbsolutePath());
				}
			}
			
			if (sourceFiles.isEmpty()) {
				logger.error("No test source files found in {}", targetDir);
				return;
			}
			
			// Step 1: Compile all test classes
			logger.info("Compiling {} test class(es)", sourceFiles.size());
			List<String> compileCmd = new ArrayList<>();
			compileCmd.add(getJavacPath());
			compileCmd.add("-cp");
			compileCmd.add(classpath);
			compileCmd.add("-d");
			compileCmd.add(sourceRoot.getAbsolutePath());
			compileCmd.addAll(sourceFiles);
			
			ProcessBuilder compileBuilder = new ProcessBuilder(compileCmd);
			compileBuilder.redirectErrorStream(true);
			Process compileProcess = compileBuilder.start();
			
			// Read compilation output
			StringBuilder compileOutput = new StringBuilder();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(compileProcess.getInputStream()))) {
				String line;
				while ((line = reader.readLine()) != null) {
					compileOutput.append(line).append("\n");
					if (line.contains("error:")) {
						logger.error("javac: {}", line);
					} else {
						logger.debug("javac: {}", line);
					}
				}
			}
			
			int compileExitCode = compileProcess.waitFor();
			if (compileExitCode != 0) {
				logger.error("Compilation failed with exit code: {}", compileExitCode);
				if (compileOutput.length() > 0) {
					logger.error("Compiler output:\n{}", compileOutput.toString().substring(0, Math.min(2000, compileOutput.length())));
				}
				return;
			}
			logger.info("Compilation successful");
			
			// Step 2: Run the tests with JUnitCore directly
			logger.info("Executing tests: {}", className);
			String testClasspath = sourceRoot.getAbsolutePath() + File.pathSeparator + classpath;
			
			List<String> runCmd = new ArrayList<>();
			runCmd.add(getJavaPath());
			runCmd.add("-cp");
			runCmd.add(testClasspath);
			
			// JVM settings
			runCmd.add("-Dallure.results.directory=" + new File(allureResultsDir).getAbsolutePath());
			
			// Add-opens for Java 9+ compatibility with Groovy
			runCmd.add("--add-opens=java.base/java.lang=ALL-UNNAMED");
			runCmd.add("--add-opens=java.base/java.lang.invoke=ALL-UNNAMED");
			runCmd.add("--add-opens=java.base/java.lang.reflect=ALL-UNNAMED");
			runCmd.add("--add-opens=java.base/java.io=ALL-UNNAMED");
			runCmd.add("--add-opens=java.base/java.net=ALL-UNNAMED");
			runCmd.add("--add-opens=java.base/java.util=ALL-UNNAMED");
			
			// AspectJ weaver for Allure (optional - enhances but not required for basic Allure4 listener)
			String aspectjPath = getAspectJWeaverPath();
			if (aspectjPath != null && new File(aspectjPath).exists()) {
				runCmd.add("-javaagent:" + aspectjPath);
				logger.debug("Using AspectJ weaver: {}", aspectjPath);
			}
			
			// Use AllureTestRunner instead of JUnitCore directly for proper Allure integration
			runCmd.add("es.us.isa.restest.runners.AllureTestRunner");
			runCmd.add(className);
			
			Timer.startCounting(TEST_SUITE_EXECUTION);
			ProcessBuilder runBuilder = new ProcessBuilder(runCmd);
			runBuilder.redirectErrorStream(true);
			runBuilder.directory(new File(".").getAbsoluteFile());
			Process runProcess = runBuilder.start();
			
			// Read and log test output
			int testsRun = 0;
			int failures = 0;
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(runProcess.getInputStream()))) {
				String line;
				while ((line = reader.readLine()) != null) {
					// Log test output but filter verbose lines
					if (!line.startsWith("\tat ") && !line.startsWith("Caused by:")) {
						logger.info("TEST: {}", line);
					}
					// Parse JUnit output for summary
					if (line.contains("Tests run:")) {
						// Parse: "Tests run: X, Failures: Y"
						String[] parts = line.split(",");
						for (String part : parts) {
							part = part.trim();
							if (part.startsWith("Tests run:")) {
								testsRun = Integer.parseInt(part.replace("Tests run:", "").trim());
							} else if (part.startsWith("Failures:")) {
								failures = Integer.parseInt(part.replace("Failures:", "").trim());
							}
						}
					}
				}
			}
			
			int runExitCode = runProcess.waitFor();
			Timer.stopCounting(TEST_SUITE_EXECUTION);
			
			int successful = testsRun - failures;
			logger.info("{} tests run. Successful: {}, Failures: {}", testsRun, successful, failures);
			
			if (runExitCode != 0 && testsRun == 0) {
				logger.warn("Test execution may have failed. Exit code: {}", runExitCode);
			}
			
		} catch (Exception e) {
			logger.error("Error executing tests in separate process", e);
		}
	}
	
	/**
	 * Build the full classpath from the current classloader URLs and Maven dependencies.
	 */
	private String buildFullClasspath() {
		java.util.Set<String> entries = new java.util.LinkedHashSet<>();
		
		// Add target/classes
		entries.add(new File("target/classes").getAbsolutePath());
		
		// Add system classpath
		String sysClasspath = System.getProperty("java.class.path");
		if (sysClasspath != null) {
			for (String entry : sysClasspath.split(File.pathSeparator)) {
				File f = new File(entry);
				if (f.exists()) {
					entries.add(f.getAbsolutePath());
				}
			}
		}
		
		// Try to get URLs from thread context classloader
		java.lang.ClassLoader cl = Thread.currentThread().getContextClassLoader();
		addUrlsFromClassLoader(cl, entries);
		
		// Also add from this class's classloader
		addUrlsFromClassLoader(RESTestWorkflow.class.getClassLoader(), entries);
		
		StringBuilder sb = new StringBuilder();
		for (String entry : entries) {
			if (sb.length() > 0) {
				sb.append(File.pathSeparator);
			}
			sb.append(entry);
		}
		return sb.toString();
	}
	
	private void addUrlsFromClassLoader(java.lang.ClassLoader cl, java.util.Set<String> entries) {
		while (cl != null) {
			if (cl instanceof java.net.URLClassLoader) {
				for (java.net.URL url : ((java.net.URLClassLoader) cl).getURLs()) {
					if ("file".equals(url.getProtocol())) {
						try {
							String path = new File(url.toURI()).getAbsolutePath();
							entries.add(path);
						} catch (Exception e) {
							// Ignore
						}
					}
				}
			}
			cl = cl.getParent();
		}
	}
	
	private String getJavacPath() {
		String javaHome = System.getProperty("java.home");
		// java.home points to JRE, we need JDK's javac
		File javac = new File(javaHome, "bin/javac");
		if (!javac.exists()) {
			javac = new File(javaHome, "bin/javac.exe");
		}
		if (!javac.exists()) {
			// Try parent directory (JDK structure)
			javac = new File(javaHome + "/../bin/javac");
			if (!javac.exists()) {
				javac = new File(javaHome + "/../bin/javac.exe");
			}
		}
		return javac.exists() ? javac.getAbsolutePath() : "javac";
	}
	
	private String getJavaPath() {
		String javaHome = System.getProperty("java.home");
		File java = new File(javaHome, "bin/java");
		if (!java.exists()) {
			java = new File(javaHome, "bin/java.exe");
		}
		return java.exists() ? java.getAbsolutePath() : "java";
	}
	
	private String getAspectJWeaverPath() {
		// Try to find aspectjweaver in the classpath
		String classpath = System.getProperty("java.class.path");
		for (String entry : classpath.split(File.pathSeparator)) {
			if (entry.contains("aspectjweaver")) {
				return entry;
			}
		}
		// Fallback to Maven repository location - try multiple versions
		String m2Repo = System.getProperty("user.home") + "/.m2/repository";
		String[] versions = {"1.9.21", "1.9.20", "1.9.19", "1.9.7", "1.9.6"};
		for (String version : versions) {
			String path = m2Repo + "/org/aspectj/aspectjweaver/" + version + "/aspectjweaver-" + version + ".jar";
			if (new File(path).exists()) {
				return path;
			}
		}
		return null;
	}

	protected void testExecution(Class<?> testClass)  {
		
		// Set the thread context classloader to the test class's classloader
		// This helps avoid classloader conflicts with Groovy (used by RestAssured)
		java.lang.ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
		Thread.currentThread().setContextClassLoader(testClass.getClassLoader());
		
		try {
			JUnitCore junit = new JUnitCore();
			//junit.addListener(new TextListener(System.out));
			junit.addListener(new io.qameta.allure.junit4.AllureJunit4());
			Timer.startCounting(TEST_SUITE_EXECUTION);
			Result result = junit.run(testClass);
			Timer.stopCounting(TEST_SUITE_EXECUTION);
			int successfulTests = result.getRunCount() - result.getFailureCount() - result.getIgnoreCount();
			logger.info("{} tests run in {} seconds. Successful: {}, Failures: {}, Ignored: {}", result.getRunCount(), result.getRunTime()/1000, successfulTests, result.getFailureCount(), result.getIgnoreCount());
			
			// Log any failures for debugging
			if (!result.getFailures().isEmpty()) {
				for (org.junit.runner.notification.Failure failure : result.getFailures()) {
					logger.error("Test failure: {} - {}", failure.getDescription(), failure.getMessage());
					if (failure.getException() != null) {
						logger.error("Exception: ", failure.getException());
					}
				}
			}
		} finally {
			// Restore original classloader
			Thread.currentThread().setContextClassLoader(originalClassLoader);
		}

	}
	
	public String getTargetDir() {
		return targetDir;
	}
	
	public void setTargetDir(String targetDir) {
		this.targetDir = targetDir;
	}
	
	public String getTestClassName() {
		return testClassName;
	}
	
	public void setTestClassName(String testClassName) {
		this.testClassName = testClassName;
	}

	public int getNumTestCases() {
		return numTestCases;
	}
	
	public void resetNumTestCases() {
		this.numTestCases=0;
	}

	public void setTestId(String testId) {

		this.testId = testId;
	}

	public void setExecuteTestCases(Boolean executeTestCases) {
		this.executeTestCases = executeTestCases;
	}

	public void setAllureReport(boolean allureReports) {
		this.allureReports = allureReports;
	}
}
