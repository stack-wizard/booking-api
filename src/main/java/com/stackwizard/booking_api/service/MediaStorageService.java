package com.stackwizard.booking_api.service;

import org.springframework.web.multipart.MultipartFile;

public interface MediaStorageService {
    String upload(String category, Long tenantId, String baseName, MultipartFile file);
    String uploadPublic(String category, Long tenantId, String baseName, MultipartFile file);
}
