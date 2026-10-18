package com.smt.meeting.controller;

import com.smt.meeting.service.LocalObjectStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/files/local")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "storage.provider", havingValue = "local")
@CrossOrigin(
        origins = "http://localhost:5173",
        methods = {RequestMethod.PUT, RequestMethod.GET, RequestMethod.OPTIONS},
        exposedHeaders = "ETag")
public class LocalFileController {

    private final LocalObjectStorage storage;

    @PutMapping("/parts/{uploadId}/{partNumber}")
    public ResponseEntity<Void> putPart(@PathVariable String uploadId,
                                        @PathVariable int partNumber,
                                        @RequestParam long expires,
                                        @RequestParam String sig,
                                        @RequestBody byte[] body) {
        if (!storage.verifyToken("PUT", uploadId, partNumber, expires, sig)) {
            return ResponseEntity.status(403).build();
        }
        String etag = storage.writePart(uploadId, partNumber, body);
        return ResponseEntity.ok().eTag("\"" + etag + "\"").build();
    }

    @GetMapping("/objects/{uploadId}")
    public ResponseEntity<byte[]> getObject(@PathVariable String uploadId,
                                            @RequestParam long expires,
                                            @RequestParam String sig) {
        if (!storage.verifyToken("GET", uploadId, null, expires, sig)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(storage.contentType(uploadId)))
                .body(storage.readObject(uploadId));
    }
}
