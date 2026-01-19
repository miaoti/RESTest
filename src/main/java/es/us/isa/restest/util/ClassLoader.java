package es.us.isa.restest.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * 
 * @author Sergio Segura
 */
public class ClassLoader {

	private static final Logger logger = LogManager.getLogger(ClassLoader.class.getName());

	public static Class<?> loadClass(String filePath, String className) {
		File sourceFile = new File(filePath);
		Class<?> loadedClass= null;
		
		// Compile the source file 
		try {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			logger.error("Java compiler not available. Make sure you're running with a JDK, not just a JRE.");
			return null;
		}
		
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null);
		
		// Calculate the package root directory from the class name
		// e.g., className = "trainticket.TrainTicketTest" -> package depth = 1
		// sourceFile parent = src/generation/java/trainticket
		// package root = src/generation/java (go up 1 level for 1 package component)
		File parentDirectory = sourceFile.getParentFile();
		int packageDepth = className.split("\\.").length - 1; // number of package components
		File packageRoot = parentDirectory;
		for (int i = 0; i < packageDepth; i++) {
			packageRoot = packageRoot.getParentFile();
		}
		
		logger.debug("Setting CLASS_OUTPUT to: {}", packageRoot.getAbsolutePath());
		fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Arrays.asList(packageRoot));
		
		// Build classpath from current classloader URLs (works better with Maven exec:java)
		String classpath = buildClasspathFromClassLoader();
		logger.debug("Using classpath for compilation: {} chars", classpath.length());
		
		List<String> optionList = new ArrayList<>();
		optionList.add("-classpath");
		optionList.add(classpath);
		// Add source/target compatibility
		optionList.add("-source");
		optionList.add("11");
		optionList.add("-target");
		optionList.add("11");
		
		Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromFiles(Arrays.asList(sourceFile));
		StringWriter compilerOutput = new StringWriter();
		boolean success = compiler.getTask(compilerOutput, fileManager, diagnostics, optionList, null, compilationUnits).call();
		fileManager.close();
		
		if (!success) {
			logger.error("Compilation failed for: {}", filePath);
			diagnostics.getDiagnostics().forEach(d -> logger.error("Compilation error: {}", d.getMessage(null)));
			if (compilerOutput.toString().length() > 0) {
				logger.error("Compiler output: {}", compilerOutput.toString());
			}
			return null;
		}
		
		 // load the compiled class using the package root
		 loadedClass = loadClass(packageRoot, className);

		} catch (IOException e) {
			logger.error("Error loading class");
			logger.error("Exception: ", e);
		} catch (NullPointerException e) {
			logger.error("Error loading class. Make sure JDK is used");
			logger.error("Exception: ", e);
		}
		
		return loadedClass;
	}
	
	/**
	 * Build classpath string from the current thread's classloader URLs.
	 * This works better with Maven exec:java which uses a custom classloader.
	 */
	private static String buildClasspathFromClassLoader() {
		Set<String> classpathEntries = new HashSet<>();
		
		// Start with system classpath
		String systemClasspath = System.getProperty("java.class.path");
		if (systemClasspath != null && !systemClasspath.isEmpty()) {
			for (String entry : systemClasspath.split(File.pathSeparator)) {
				classpathEntries.add(entry);
			}
		}
		
		// Collect URLs from all classloaders in the hierarchy
		collectUrlsFromClassLoader(Thread.currentThread().getContextClassLoader(), classpathEntries);
		collectUrlsFromClassLoader(ClassLoader.class.getClassLoader(), classpathEntries);
		
		// Build the classpath string
		StringBuilder classpath = new StringBuilder();
		for (String entry : classpathEntries) {
			if (classpath.length() > 0) {
				classpath.append(File.pathSeparator);
			}
			classpath.append(entry);
		}
		
		return classpath.toString();
	}
	
	/**
	 * Collect URLs from a classloader, handling both URLClassLoader and modern classloaders.
	 */
	private static void collectUrlsFromClassLoader(java.lang.ClassLoader cl, Set<String> classpathEntries) {
		while (cl != null) {
			URL[] urls = getClassLoaderUrls(cl);
			if (urls != null) {
				for (URL url : urls) {
					if ("file".equals(url.getProtocol())) {
						try {
							String path = new File(url.toURI()).getAbsolutePath();
							classpathEntries.add(path);
						} catch (Exception e) {
							// Try direct path conversion
							String path = url.getPath();
							if (path != null && !path.isEmpty()) {
								// Handle Windows paths that start with /
								if (File.separatorChar == '\\' && path.startsWith("/") && path.length() > 2 && path.charAt(2) == ':') {
									path = path.substring(1);
								}
								classpathEntries.add(path);
							}
						}
					}
				}
			}
			cl = cl.getParent();
		}
	}
	
	/**
	 * Get URLs from a classloader using reflection if necessary (for Java 9+).
	 */
	private static URL[] getClassLoaderUrls(java.lang.ClassLoader cl) {
		if (cl instanceof URLClassLoader) {
			return ((URLClassLoader) cl).getURLs();
		}
		
		// For Java 9+ or custom classloaders, try reflection
		try {
			// Try the ucp field used by some classloader implementations
			java.lang.reflect.Field ucpField = null;
			Class<?> clazz = cl.getClass();
			while (clazz != null) {
				try {
					ucpField = clazz.getDeclaredField("ucp");
					break;
				} catch (NoSuchFieldException e) {
					clazz = clazz.getSuperclass();
				}
			}
			
			if (ucpField != null) {
				ucpField.setAccessible(true);
				Object ucp = ucpField.get(cl);
				if (ucp != null) {
					Method getURLsMethod = ucp.getClass().getMethod("getURLs");
					return (URL[]) getURLsMethod.invoke(ucp);
				}
			}
		} catch (Exception e) {
			// Reflection failed, that's okay
			logger.debug("Could not extract URLs from classloader via reflection: {}", e.getMessage());
		}
		
		return null;
	}

	// Keep the classloader alive so loaded classes can be used
	private static URLClassLoader testClassLoader = null;
	
	private static Class<?> loadClass(File parentDirectory, String className) {
		Class<?> loadedClass= null;
		// Include current classpath in the URLClassLoader so loaded class can access dependencies
		try {
			URL newUrl = parentDirectory.toURI().toURL();
			
			// Get the thread context classloader
			java.lang.ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
			if (contextLoader == null) {
				contextLoader = ClassLoader.class.getClassLoader();
			}
			
			// Try to add URL to the existing classloader using reflection (works with URLClassLoader)
			boolean addedToExisting = false;
			if (contextLoader instanceof URLClassLoader) {
				try {
					java.lang.reflect.Method addURL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
					addURL.setAccessible(true);
					addURL.invoke(contextLoader, newUrl);
					loadedClass = contextLoader.loadClass(className);
					addedToExisting = true;
					logger.debug("Successfully loaded class: {} by adding URL to existing classloader", className);
				} catch (Exception e) {
					logger.debug("Could not add URL to existing classloader: {}", e.getMessage());
				}
			}
			
			// Fallback: Create a new URLClassLoader
			if (!addedToExisting) {
				// Close any previous classloader
				if (testClassLoader != null) {
					try {
						testClassLoader.close();
					} catch (IOException e) {
						// Ignore
					}
				}
				
				// Create classloader with the parent that has access to all dependencies
				testClassLoader = new URLClassLoader(new URL[]{newUrl}, contextLoader);
				loadedClass = testClassLoader.loadClass(className);
				logger.debug("Successfully loaded class: {} with new URLClassLoader, parent: {}", className, contextLoader.getClass().getName());
			}
		} catch (IOException e) {
			logger.error("Error loading class");
			logger.error("Exception: ", e);
		} catch (ClassNotFoundException e) {
			logger.error("Class not found: {}", className);
			logger.error("Exception: ", e);
		}

		return loadedClass;
	}
}
