package es.us.isa.restest.runners;

import io.qameta.allure.junit4.AllureJunit4;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;

/**
 * A JUnit test runner that includes Allure listener for reporting.
 * This class is designed to be run in a separate process.
 */
public class AllureTestRunner {
    
    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: AllureTestRunner <className>");
            System.exit(1);
        }
        
        String className = args[0];
        
        try {
            Class<?> testClass = Class.forName(className);
            
            JUnitCore junit = new JUnitCore();
            
            // Add Allure listener for reporting
            junit.addListener(new AllureJunit4());
            
            System.out.println("Running tests for: " + className);
            Result result = junit.run(testClass);
            
            int successfulTests = result.getRunCount() - result.getFailureCount() - result.getIgnoreCount();
            
            // Output in a parseable format
            System.out.println("Tests run: " + result.getRunCount() + 
                             ",  Failures: " + result.getFailureCount() +
                             ",  Ignored: " + result.getIgnoreCount() +
                             ",  Time elapsed: " + (result.getRunTime() / 1000.0) + " s");
            
            // Exit with non-zero if there were failures
            System.exit(result.getFailureCount() > 0 ? 1 : 0);
            
        } catch (ClassNotFoundException e) {
            System.err.println("Could not find test class: " + className);
            e.printStackTrace();
            System.exit(1);
        }
    }
}
