// pom.xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>config-manager</artifactId>
    <version>1.0.0</version>
    
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>2.7.0</version>
    </parent>
    
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>com.oracle.database.jdbc</groupId>
            <artifactId>ojdbc8</artifactId>
        </dependency>
        <dependency>
            <groupId>com.google.cloud</groupId>
            <artifactId>google-cloud-storage</artifactId>
            <version>2.22.0</version>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>
</project>

// src/main/resources/application.yml
spring:
  datasource:
    url: jdbc:oracle:thin:@//localhost:1521/XEPDB1
    username: your_username
    password: your_password
    driver-class-name: oracle.jdbc.OracleDriver
  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        dialect: org.hibernate.dialect.Oracle12cDialect

gcs:
  bucket-name: your-bucket-name
  project-id: your-project-id

// src/main/java/com/example/configmanager/model/ConfigurationScript.java
package com.example.configmanager.model;

import lombok.Data;
import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "configuration_scripts")
public class ConfigurationScript {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String scriptContent;
    private Integer scriptOrder;
    private String folderTimestamp;
    private LocalDateTime createdAt;
    private String status; // PENDING, DEPLOYED, REVERTED
}

// src/main/java/com/example/configmanager/service/GCSService.java
package com.example.configmanager.service;

import com.google.cloud.storage.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;

@Service
public class GCSService {
    private final Storage storage;
    
    @Value("${gcs.bucket-name}")
    private String bucketName;
    
    public GCSService(@Value("${gcs.project-id}") String projectId) {
        this.storage = StorageOptions.newBuilder()
            .setProjectId(projectId)
            .build()
            .getService();
    }
    
    public void uploadScript(String folderName, String fileName, String content) {
        BlobId blobId = BlobId.of(bucketName, folderName + "/" + fileName);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();
        storage.create(blobInfo, content.getBytes(StandardCharsets.UTF_8));
    }
    
    public String downloadScript(String folderName, String fileName) {
        Blob blob = storage.get(BlobId.of(bucketName, folderName + "/" + fileName));
        return new String(blob.getContent(), StandardCharsets.UTF_8);
    }
}

// src/main/java/com/example/configmanager/service/ConfigurationService.java
package com.example.configmanager.service;

import com.example.configmanager.model.ConfigurationScript;
import com.example.configmanager.repository.ConfigurationScriptRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class ConfigurationService {
    private final ConfigurationScriptRepository repository;
    private final GCSService gcsService;
    private final OracleService oracleService;
    
    public ConfigurationService(ConfigurationScriptRepository repository, 
                              GCSService gcsService,
                              OracleService oracleService) {
        this.repository = repository;
        this.gcsService = gcsService;
        this.oracleService = oracleService;
    }
    
    @Transactional
    public void saveScripts(List<String> scripts) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        
        for (int i = 0; i < scripts.size(); i++) {
            ConfigurationScript script = new ConfigurationScript();
            script.setScriptContent(scripts.get(i));
            script.setScriptOrder(i + 1);
            script.setFolderTimestamp(timestamp);
            script.setCreatedAt(LocalDateTime.now());
            script.setStatus("PENDING");
            
            repository.save(script);
            gcsService.uploadScript(timestamp, "script" + (i + 1) + ".sql", scripts.get(i));
        }
    }
    
    @Transactional
    public void deployScripts(String folderTimestamp) {
        List<ConfigurationScript> scripts = repository.findByFolderTimestampOrderByScriptOrder(folderTimestamp);
        
        for (ConfigurationScript script : scripts) {
            try {
                oracleService.executeScript(script.getScriptContent());
                script.setStatus("DEPLOYED");
                repository.save(script);
            } catch (Exception e) {
                script.setStatus("FAILED");
                repository.save(script);
                throw new RuntimeException("Deployment failed at script " + script.getScriptOrder(), e);
            }
        }
    }
    
    @Transactional
    public void revertToLastSuccessful() {
        String lastSuccessfulTimestamp = repository.findLastSuccessfulDeployment();
        if (lastSuccessfulTimestamp == null) {
            throw new RuntimeException("No successful deployment found to revert to");
        }
        
        List<ConfigurationScript> scripts = repository.findByFolderTimestampOrderByScriptOrder(lastSuccessfulTimestamp);
        for (ConfigurationScript script : scripts) {
            oracleService.executeScript(script.getScriptContent());
        }
    }
}

// src/main/java/com/example/configmanager/controller/ConfigurationController.java
package com.example.configmanager.controller;

import com.example.configmanager.service.ConfigurationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/config")
public class ConfigurationController {
    private final ConfigurationService service;
    
    public ConfigurationController(ConfigurationService service) {
        this.service = service;
    }
    
    @PostMapping("/save")
    public void saveScripts(@RequestBody List<String> scripts) {
        service.saveScripts(scripts);
    }
    
    @PostMapping("/deploy/{timestamp}")
    @PreAuthorize("hasRole('DEPLOYER')")
    public void deployScripts(@PathVariable String timestamp) {
        service.deployScripts(timestamp);
    }
    
    @PostMapping("/revert")
    @PreAuthorize("hasRole('DEPLOYER')")
    public void revertToLastSuccessful() {
        service.revertToLastSuccessful();
    }
}
