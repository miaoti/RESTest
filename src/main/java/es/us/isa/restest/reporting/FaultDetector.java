package es.us.isa.restest.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Stream;

/**
 * Detects injected faults from test results by analyzing Allure result files.
 * Looks for responses containing "isInjected": true and logs detected faults.
 */
public class FaultDetector {

    private static final Logger logger = LogManager.getLogger(FaultDetector.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final String allureResultsDir;
    private final String faultLogDir;
    private final List<DetectedFault> detectedFaults = new ArrayList<>();
    
    // Known injected faults - can be loaded from JSON file or use defaults
    private final Map<String, FaultInfo> knownFaults = new HashMap<>();
    
    public FaultDetector(String allureResultsDir, String faultLogDir) {
        this.allureResultsDir = allureResultsDir;
        this.faultLogDir = faultLogDir;
        loadDefaultFaults();
    }
    
    public FaultDetector(String allureResultsDir, String faultLogDir, String injectedFaultsJsonPath) {
        this.allureResultsDir = allureResultsDir;
        this.faultLogDir = faultLogDir;
        if (injectedFaultsJsonPath != null && !injectedFaultsJsonPath.isEmpty()) {
            loadFaultsFromJson(injectedFaultsJsonPath);
        } else {
            loadDefaultFaults();
        }
    }
    
    /**
     * Load known faults from an injected-faults.json file.
     */
    public void loadFaultsFromJson(String jsonPath) {
        try {
            File jsonFile = new File(jsonPath);
            if (!jsonFile.exists()) {
                logger.warn("Injected faults JSON file not found: {}. Using defaults.", jsonPath);
                loadDefaultFaults();
                return;
            }
            
            JsonNode root = objectMapper.readTree(jsonFile);
            if (root.has("injected_faults")) {
                JsonNode faults = root.get("injected_faults");
                for (JsonNode fault : faults) {
                    String faultName = fault.has("faultName") ? fault.get("faultName").asText() : null;
                    String service = fault.has("service") ? fault.get("service").asText() : "";
                    String api = fault.has("api") ? fault.get("api").asText() : "";
                    
                    if (faultName != null) {
                        knownFaults.put(faultName, new FaultInfo(service, api));
                    }
                }
                logger.info("Loaded {} known faults from {}", knownFaults.size(), jsonPath);
            }
        } catch (IOException e) {
            logger.error("Error loading injected faults JSON: {}", e.getMessage());
            loadDefaultFaults();
        }
    }
    
    private void loadDefaultFaults() {
        knownFaults.put("INVALID_CONTACTS_NAME_FAULT", new FaultInfo("ts-admin-order-service", 
            "POST /api/v1/adminorderservice/adminorder, PUT /api/v1/adminorderservice/adminorder"));
        knownFaults.put("INVALID_SEAT_NUMBER_FAULT", new FaultInfo("ts-admin-order-service",
            "POST /api/v1/adminorderservice/adminorder, PUT /api/v1/adminorderservice/adminorder"));
        knownFaults.put("INVALID_PRICE_RATE_FAULT", new FaultInfo("ts-admin-basic-info-service",
            "POST /api/v1/adminbasicservice/adminbasic/prices"));
        knownFaults.put("INVALID_ROUTE_ID_FAULT", new FaultInfo("ts-admin-basic-info-service",
            "POST /api/v1/adminbasicservice/adminbasic/prices"));
        knownFaults.put("INVALID_STATION_NAME_FAULT", new FaultInfo("ts-travel-plan-service",
            "POST /api/v1/travelplanservice/travelPlan/minStation"));
        knownFaults.put("INVALID_STATION_LENGTH_FAULT", new FaultInfo("ts-travel-plan-service",
            "POST /api/v1/travelplanservice/travelPlan/minStation"));
        knownFaults.put("INVALID_TRIP_ID_FORMAT_FAULT", new FaultInfo("ts-admin-travel-service",
            "DELETE /api/v1/admintravelservice/admintravel/{tripId}"));
        knownFaults.put("INVALID_TRIP_ID_LENGTH_FAULT", new FaultInfo("ts-admin-travel-service",
            "DELETE /api/v1/admintravelservice/admintravel/{tripId}"));
        knownFaults.put("INSUFFICIENT_STATIONS_FAULT", new FaultInfo("ts-admin-route-service",
            "POST /api/v1/adminrouteservice/adminroute"));
        knownFaults.put("INVALID_STATION_NAME_LENGTH_FAULT", new FaultInfo("ts-admin-route-service",
            "POST /api/v1/adminrouteservice/adminroute"));
    }
    
    /**
     * Analyze all test results and detect injected faults.
     */
    public void analyzeResults() {
        logger.info("Analyzing test results for injected faults...");
        detectedFaults.clear();
        
        Path resultsPath = Paths.get(allureResultsDir);
        if (!Files.exists(resultsPath)) {
            logger.warn("Allure results directory not found: {}", allureResultsDir);
            return;
        }
        
        // First, analyze result JSON files
        try (Stream<Path> files = Files.list(resultsPath)) {
            files.filter(p -> p.toString().endsWith("-result.json"))
                 .forEach(this::analyzeResultFile);
        } catch (IOException e) {
            logger.error("Error reading Allure results directory", e);
        }
        
        // Also directly scan all attachment files for fault responses
        try (Stream<Path> files = Files.list(resultsPath)) {
            files.filter(p -> p.toString().endsWith("-attachment.html"))
                 .forEach(this::analyzeAttachmentDirect);
        } catch (IOException e) {
            logger.error("Error scanning attachment files", e);
        }
        
        logger.info("Fault detection complete. Found {} injected faults.", detectedFaults.size());
    }
    
    /**
     * Directly analyze an attachment HTML file for injected faults.
     */
    private void analyzeAttachmentDirect(Path attachmentFile) {
        try {
            String content = Files.readString(attachmentFile);
            String fileName = attachmentFile.getFileName().toString();
            checkForFaultInText(content, "attachment:" + fileName, "unknown");
        } catch (Exception e) {
            logger.debug("Error reading attachment file {}: {}", attachmentFile, e.getMessage());
        }
    }
    
    /**
     * Analyze a single Allure result file for injected faults.
     */
    private void analyzeResultFile(Path resultFile) {
        try {
            String content = Files.readString(resultFile);
            JsonNode resultNode = objectMapper.readTree(content);
            
            // Get test name and status
            String testName = resultNode.has("name") ? resultNode.get("name").asText() : "unknown";
            String status = resultNode.has("status") ? resultNode.get("status").asText() : "unknown";
            
            // Check attachments for response bodies
            if (resultNode.has("attachments")) {
                JsonNode attachments = resultNode.get("attachments");
                for (JsonNode attachment : attachments) {
                    String attachmentName = attachment.has("name") ? attachment.get("name").asText() : "";
                    if (attachmentName.toLowerCase().contains("response")) {
                        String source = attachment.has("source") ? attachment.get("source").asText() : "";
                        if (!source.isEmpty()) {
                            analyzeAttachment(resultFile.getParent(), source, testName, status);
                        }
                    }
                }
            }
            
            // Also check statusDetails for fault information in the error message
            if (resultNode.has("statusDetails")) {
                JsonNode statusDetails = resultNode.get("statusDetails");
                String message = statusDetails.has("message") ? statusDetails.get("message").asText() : "";
                String trace = statusDetails.has("trace") ? statusDetails.get("trace").asText() : "";
                
                // Check if the error message contains injected fault indicators
                checkForFaultInText(message + " " + trace, testName, status);
            }
            
        } catch (Exception e) {
            logger.debug("Error analyzing result file {}: {}", resultFile, e.getMessage());
        }
    }
    
    /**
     * Analyze an attachment file for injected fault responses.
     */
    private void analyzeAttachment(Path parentDir, String source, String testName, String status) {
        try {
            Path attachmentPath = parentDir.resolve(source);
            if (Files.exists(attachmentPath)) {
                String content = Files.readString(attachmentPath);
                checkForFaultInText(content, testName, status);
            }
        } catch (Exception e) {
            logger.debug("Error reading attachment {}: {}", source, e.getMessage());
        }
    }
    
    /**
     * Check text content for injected fault indicators.
     */
    private void checkForFaultInText(String content, String testName, String status) {
        if (content == null || content.isEmpty()) {
            return;
        }
        
        // Unescape HTML entities
        String unescaped = content
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#39;", "'");
        
        // Look for isInjected: true or injected: true pattern
        boolean hasInjectedFlag = (unescaped.contains("\"isInjected\"") || unescaped.contains("\"injected\"")) 
            && unescaped.contains("true");
        
        if (hasInjectedFlag) {
            // Try to parse as JSON to extract fault details
            try {
                // Find JSON object in unescaped content
                int start = unescaped.indexOf("{");
                int end = unescaped.lastIndexOf("}");
                if (start >= 0 && end > start) {
                    String jsonPart = unescaped.substring(start, end + 1);
                    JsonNode responseNode = objectMapper.readTree(jsonPart);
                    
                    // Check for fault in data field
                    if (responseNode.has("data")) {
                        JsonNode data = responseNode.get("data");
                        // Check both "isInjected" and "injected" fields
                        boolean isInjected = (data.has("isInjected") && data.get("isInjected").asBoolean())
                            || (data.has("injected") && data.get("injected").asBoolean());
                        
                        if (isInjected) {
                            String faultName = data.has("faultName") ? data.get("faultName").asText() : "UNKNOWN_FAULT";
                            String message = data.has("message") ? data.get("message").asText() : "";
                            String details = data.has("details") ? data.get("details").asText() : "";
                            
                            DetectedFault fault = new DetectedFault(faultName, testName, status, message, details);
                            if (!detectedFaults.contains(fault)) {
                                detectedFaults.add(fault);
                                logger.info("FAULT DETECTED: {} in test {}", faultName, testName);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // If JSON parsing fails, try regex-based extraction
                extractFaultFromText(unescaped, testName, status);
            }
        }
        
        // Also check for known fault names in the unescaped content
        for (String faultName : knownFaults.keySet()) {
            if (unescaped.contains(faultName)) {
                DetectedFault fault = new DetectedFault(faultName, testName, status, 
                    "Fault name found in response", "");
                if (!detectedFaults.contains(fault)) {
                    detectedFaults.add(fault);
                    logger.info("FAULT DETECTED (by name): {} in test {}", faultName, testName);
                }
            }
        }
    }
    
    /**
     * Extract fault information using pattern matching when JSON parsing fails.
     */
    private void extractFaultFromText(String content, String testName, String status) {
        for (String faultName : knownFaults.keySet()) {
            if (content.contains(faultName)) {
                DetectedFault fault = new DetectedFault(faultName, testName, status,
                    "Detected via pattern matching", "");
                if (!detectedFaults.contains(fault)) {
                    detectedFaults.add(fault);
                }
            }
        }
    }
    
    /**
     * Generate a fault detection report and save to log file.
     */
    public void generateReport() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        String logFileName = "fault-detection-report_" + timestamp + ".log";
        
        // Create log directory if it doesn't exist
        File logDir = new File(faultLogDir);
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
        
        File logFile = new File(logDir, logFileName);
        
        try (PrintWriter writer = new PrintWriter(new FileWriter(logFile))) {
            writer.println("=" .repeat(80));
            writer.println("INJECTED FAULT DETECTION REPORT");
            writer.println("=" .repeat(80));
            writer.println("Generated: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            writer.println("Allure Results Directory: " + allureResultsDir);
            writer.println();
            
            writer.println("-".repeat(80));
            writer.println("SUMMARY");
            writer.println("-".repeat(80));
            writer.println("Total Known Injected Faults: " + knownFaults.size());
            writer.println("Total Detected Faults: " + detectedFaults.size());
            writer.println();
            
            // Count unique fault types detected
            Set<String> uniqueFaults = new HashSet<>();
            for (DetectedFault fault : detectedFaults) {
                uniqueFaults.add(fault.faultName);
            }
            writer.println("Unique Fault Types Detected: " + uniqueFaults.size() + " / " + knownFaults.size());
            writer.println();
            
            // List detected faults
            writer.println("-".repeat(80));
            writer.println("DETECTED FAULTS");
            writer.println("-".repeat(80));
            
            if (detectedFaults.isEmpty()) {
                writer.println("No injected faults were detected in this test run.");
            } else {
                int count = 1;
                for (DetectedFault fault : detectedFaults) {
                    writer.println();
                    writer.println(count + ". " + fault.faultName);
                    writer.println("   Test: " + fault.testName);
                    writer.println("   Status: " + fault.testStatus);
                    writer.println("   Message: " + fault.message);
                    if (!fault.details.isEmpty()) {
                        writer.println("   Details: " + fault.details);
                    }
                    FaultInfo info = knownFaults.get(fault.faultName);
                    if (info != null) {
                        writer.println("   Service: " + info.service);
                        writer.println("   API: " + info.api);
                    }
                    count++;
                }
            }
            
            // List undetected faults
            writer.println();
            writer.println("-".repeat(80));
            writer.println("UNDETECTED FAULTS (from known list)");
            writer.println("-".repeat(80));
            
            List<String> undetectedFaults = new ArrayList<>();
            for (String faultName : knownFaults.keySet()) {
                if (!uniqueFaults.contains(faultName)) {
                    undetectedFaults.add(faultName);
                }
            }
            
            if (undetectedFaults.isEmpty()) {
                writer.println("All known injected faults were detected!");
            } else {
                writer.println("The following " + undetectedFaults.size() + " faults were NOT detected:");
                for (String faultName : undetectedFaults) {
                    FaultInfo info = knownFaults.get(faultName);
                    writer.println("  - " + faultName);
                    if (info != null) {
                        writer.println("    Service: " + info.service);
                        writer.println("    API: " + info.api);
                    }
                }
            }
            
            // List newly discovered faults (not in known list)
            writer.println();
            writer.println("-".repeat(80));
            writer.println("NEWLY DISCOVERED FAULTS (not in known list)");
            writer.println("-".repeat(80));
            
            List<DetectedFault> newFaults = new ArrayList<>();
            for (DetectedFault fault : detectedFaults) {
                if (!knownFaults.containsKey(fault.faultName)) {
                    newFaults.add(fault);
                }
            }
            
            if (newFaults.isEmpty()) {
                writer.println("No new faults discovered.");
            } else {
                writer.println("The following " + newFaults.size() + " faults are NEW (not in known list):");
                for (DetectedFault fault : newFaults) {
                    writer.println("  - " + fault.faultName);
                    writer.println("    Test: " + fault.testName);
                    writer.println("    Message: " + fault.message);
                }
            }
            
            writer.println();
            writer.println("=" .repeat(80));
            writer.println("END OF REPORT");
            writer.println("=" .repeat(80));
            
            logger.info("Fault detection report saved to: {}", logFile.getAbsolutePath());
            
        } catch (IOException e) {
            logger.error("Error writing fault detection report", e);
        }
        
        // Also generate JSON report
        generateJsonReport(logDir, timestamp);
    }
    
    /**
     * Generate a JSON format report for programmatic access.
     */
    private void generateJsonReport(File logDir, String timestamp) {
        String jsonFileName = "fault-detection-report_" + timestamp + ".json";
        File jsonFile = new File(logDir, jsonFileName);
        
        try {
            ObjectNode report = objectMapper.createObjectNode();
            report.put("generatedAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date()));
            report.put("allureResultsDir", allureResultsDir);
            report.put("totalKnownFaults", knownFaults.size());
            report.put("totalDetectedFaults", detectedFaults.size());
            
            // Count unique fault types
            Set<String> uniqueFaults = new HashSet<>();
            for (DetectedFault fault : detectedFaults) {
                uniqueFaults.add(fault.faultName);
            }
            report.put("uniqueFaultTypesDetected", uniqueFaults.size());
            report.put("detectionRate", String.format("%.1f%%", (uniqueFaults.size() * 100.0 / knownFaults.size())));
            
            // Add detected faults
            ArrayNode detectedArray = objectMapper.createArrayNode();
            for (DetectedFault fault : detectedFaults) {
                ObjectNode faultNode = objectMapper.createObjectNode();
                faultNode.put("faultName", fault.faultName);
                faultNode.put("testName", fault.testName);
                faultNode.put("testStatus", fault.testStatus);
                faultNode.put("message", fault.message);
                faultNode.put("details", fault.details);
                detectedArray.add(faultNode);
            }
            report.set("detectedFaults", detectedArray);
            
            // Add undetected faults
            ArrayNode undetectedArray = objectMapper.createArrayNode();
            for (String faultName : knownFaults.keySet()) {
                if (!uniqueFaults.contains(faultName)) {
                    ObjectNode faultNode = objectMapper.createObjectNode();
                    faultNode.put("faultName", faultName);
                    FaultInfo info = knownFaults.get(faultName);
                    if (info != null) {
                        faultNode.put("service", info.service);
                        faultNode.put("api", info.api);
                    }
                    undetectedArray.add(faultNode);
                }
            }
            report.set("undetectedFaults", undetectedArray);
            
            // Add newly discovered faults
            ArrayNode newFaultsArray = objectMapper.createArrayNode();
            for (DetectedFault fault : detectedFaults) {
                if (!knownFaults.containsKey(fault.faultName)) {
                    ObjectNode faultNode = objectMapper.createObjectNode();
                    faultNode.put("faultName", fault.faultName);
                    faultNode.put("testName", fault.testName);
                    faultNode.put("message", fault.message);
                    faultNode.put("details", fault.details);
                    newFaultsArray.add(faultNode);
                }
            }
            report.set("newlyDiscoveredFaults", newFaultsArray);
            
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonFile, report);
            logger.info("Fault detection JSON report saved to: {}", jsonFile.getAbsolutePath());
            
        } catch (IOException e) {
            logger.error("Error writing fault detection JSON report", e);
        }
    }
    
    public List<DetectedFault> getDetectedFaults() {
        return detectedFaults;
    }
    
    /**
     * Represents a detected fault.
     */
    public static class DetectedFault {
        public final String faultName;
        public final String testName;
        public final String testStatus;
        public final String message;
        public final String details;
        
        public DetectedFault(String faultName, String testName, String testStatus, String message, String details) {
            this.faultName = faultName;
            this.testName = testName;
            this.testStatus = testStatus;
            this.message = message;
            this.details = details;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DetectedFault that = (DetectedFault) o;
            return Objects.equals(faultName, that.faultName) && Objects.equals(testName, that.testName);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(faultName, testName);
        }
    }
    
    /**
     * Information about a known fault.
     */
    private static class FaultInfo {
        public final String service;
        public final String api;
        
        public FaultInfo(String service, String api) {
            this.service = service;
            this.api = api;
        }
    }
}
