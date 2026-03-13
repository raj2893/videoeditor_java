package com.example.videoeditor.service;

import com.example.videoeditor.entity.StandaloneImage;
import com.example.videoeditor.entity.StandaloneImageUsage;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.repository.StandaloneImageRepository;
import com.example.videoeditor.repository.StandaloneImageUsageRepository;
import com.example.videoeditor.repository.UserRepository;
import com.example.videoeditor.security.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class StandaloneImageService {

  private final JwtUtil jwtUtil;
  private final UserRepository userRepository;
  private final CreditService creditService;

  private final String baseDir = "D:\\Backend\\videoeditor_java";

  private final String backgroundRemovalScriptPath = baseDir + File.separator + "scripts" + File.separator + "remove_background.py";

  private final String pythonPath = System.getenv().getOrDefault("PYTHON_PATH", "C:\\Users\\praj1\\AppData\\Local\\Programs\\Python\\Python311\\python.exe");

  @Autowired
  private StandaloneImageRepository standaloneImageRepository;

  @Autowired
  private ObjectMapper objectMapper;

  public StandaloneImageService(JwtUtil jwtUtil, UserRepository userRepository, CreditService creditService) {
    this.jwtUtil = jwtUtil;
    this.userRepository = userRepository;
      this.creditService = creditService;
  }

  public StandaloneImage processStandaloneImageBackgroundRemoval(User user, MultipartFile imageFile)
      throws IOException, InterruptedException {

      String currentMonth = YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));

      creditService.spend(user, CreditService.COST_BG_REMOVAL, "Background removal");
      int maxDimension = creditService.isPaid(user) ? 3840 : 1280;

    // Save input file
    File originalDir = new File(baseDir, "images/standalone/" + user.getId() + "/original");
    File processedDir = new File(baseDir, "images/standalone/" + user.getId() + "/processed");

    if (!originalDir.exists() && !originalDir.mkdirs()) {
      throw new IOException("Failed to create original directory: " + originalDir.getAbsolutePath());
    }
    if (!processedDir.exists() && !processedDir.mkdirs()) {
      throw new IOException("Failed to create processed directory: " + processedDir.getAbsolutePath());
    }

    File inputFile = new File(originalDir, imageFile.getOriginalFilename());
    imageFile.transferTo(inputFile);

    String originalFileName = imageFile.getOriginalFilename();
    String outputFileName = "bg_removed_" + originalFileName.substring(0, originalFileName.lastIndexOf('.')) + ".png";
    File outputFile = new File(processedDir, outputFileName);

    // Build python command
    List<String> command = Arrays.asList(
        pythonPath,
        backgroundRemovalScriptPath,
        inputFile.getAbsolutePath(),
        outputFile.getAbsolutePath(),
        String.valueOf(maxDimension)
    );

    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    Process process = pb.start();

    StringBuilder output = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        output.append(line).append("\n");
      }
    }

    int exitCode = process.waitFor();
    if (exitCode != 0) {
      throw new IOException("Background removal failed with exit=" + exitCode + ", logs=" + output);
    }

    if (!outputFile.exists()) {
      throw new IOException("Output file not created: " + outputFile.getAbsolutePath());
    }

      try {
          String jsonOutput = output.toString().trim();
          if (jsonOutput.startsWith("{")) {
              JSONObject result = new JSONObject(jsonOutput);
              if (result.has("width") && result.has("height")) {
                  System.out.println("Processed image dimensions: " + result.getInt("width") + "x" + result.getInt("height"));
              }
          }
      } catch (Exception e) {
          // Ignore JSON parsing errors - the file was created successfully
          System.out.println("Could not parse output JSON, but file was created successfully");
      }

    // Save standalone image record
    StandaloneImage standaloneImage = new StandaloneImage();
    standaloneImage.setUser(user);
    standaloneImage.setOriginalFileName(inputFile.getName());
    standaloneImage.setOriginalPath("images/standalone/" + user.getId() + "/" + inputFile.getName());
    standaloneImage.setProcessedPath("images/standalone/" + user.getId() + "/" + outputFileName);
    standaloneImage.setProcessedFileName(outputFileName);
    standaloneImage.setStatus("SUCCESS");
    standaloneImage.setCreatedAt(LocalDateTime.now());
    standaloneImageRepository.save(standaloneImage);

    return standaloneImage;
  }


  public User getUserFromToken(String token) {
    String email = jwtUtil.extractEmail(token.substring(7));
    return userRepository.findByEmail(email)
        .orElseThrow(() -> new RuntimeException("User not found"));
  }

    public Map<String, Object> getUserBackgroundRemovalStats(User user) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("balance", creditService.getBalance(user));
        stats.put("costPerRemoval", CreditService.COST_BG_REMOVAL);
        stats.put("isPaid", creditService.isPaid(user));
        return stats;
    }

  public List<StandaloneImage> getUserImages(User user) {
    return standaloneImageRepository.findByUser(user);
  }
}