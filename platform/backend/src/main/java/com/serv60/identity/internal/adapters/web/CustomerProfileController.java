package com.serv60.identity.internal.adapters.web;

import com.serv60.identity.api.CustomerProfileContracts.*;
import com.serv60.identity.internal.application.CustomerProfileService;
import com.serv60.security.CustomerAuthorization;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/customer")
@Validated
public class CustomerProfileController {
  private final CustomerProfileService profiles;
  private final CustomerAuthorization identities;

  public CustomerProfileController(
      CustomerProfileService profiles, CustomerAuthorization identities) {
    this.profiles = profiles;
    this.identities = identities;
  }

  @GetMapping("/profile")
  public ResponseEntity<Profile> get(Authentication auth) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(profiles.get(identities.customerId(auth)));
  }

  @PutMapping("/profile")
  public ResponseEntity<Profile> save(Authentication auth, @Valid @RequestBody SaveProfile body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(profiles.save(identities.customerId(auth), body));
  }
}
