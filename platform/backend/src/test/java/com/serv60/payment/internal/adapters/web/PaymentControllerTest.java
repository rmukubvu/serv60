package com.serv60.payment.internal.adapters.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.serv60.booking.internal.adapters.web.ApiExceptionHandler;
import com.serv60.payment.api.PaymentContracts;
import com.serv60.payment.internal.application.PaymentService;
import com.serv60.security.CustomerAuthorization;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {
  private static final UUID CUSTOMER = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID HOLD = UUID.fromString("00000000-0000-0000-0000-000000000099");
  private static final UUID PAYMENT = UUID.fromString("00000000-0000-0000-0000-000000000098");

  @Mock PaymentService payments;
  @Mock CustomerAuthorization customerAuthorization;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.standaloneSetup(new PaymentController(payments, customerAuthorization))
        .setControllerAdvice(new ApiExceptionHandler()).build();
  }

  @Test
  void createsPaymentOrderWithIdempotencyKey() throws Exception {
    var order = new PaymentContracts.PaymentOrder(PAYMENT, CUSTOMER, HOLD, 24900, "ZAR", "YOCO",
        "PENDING", null, OffsetDateTime.parse("2026-10-12T08:00:00Z"));
    when(payments.create(any(), eq("payment-key"))).thenReturn(order);

    mvc.perform(post("/v1/payments").header("Idempotency-Key", "payment-key")
            .contentType("application/json").content("""
                {"customerId":"00000000-0000-0000-0000-000000000001","holdId":"00000000-0000-0000-0000-000000000099","amountMinor":24900,"currency":"ZAR","provider":"YOCO"}
                """))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(PAYMENT.toString()))
        .andExpect(jsonPath("$.status").value("PENDING"));
  }

  @Test
  void findsPaymentOrder() throws Exception {
    var order = new PaymentContracts.PaymentOrder(PAYMENT, CUSTOMER, HOLD, 24900, "ZAR", "PEACH",
        "PENDING", null, OffsetDateTime.parse("2026-10-12T08:00:00Z"));
    when(payments.find(PAYMENT)).thenReturn(Optional.of(order));

    mvc.perform(get("/v1/payments/{id}", PAYMENT)).andExpect(status().isOk())
        .andExpect(jsonPath("$.provider").value("PEACH"));
  }

  @Test
  void missingPaymentIsNotFound() throws Exception {
    when(payments.find(PAYMENT)).thenReturn(Optional.empty());
    mvc.perform(get("/v1/payments/{id}", PAYMENT)).andExpect(status().isNotFound());
  }

  @Test
  void authorizationFailureIsForbidden() throws Exception {
    doThrow(new AccessDeniedException("customer access denied"))
        .when(customerAuthorization).require(eq(CUSTOMER), any());
    when(payments.find(PAYMENT)).thenReturn(Optional.of(new PaymentContracts.PaymentOrder(PAYMENT,
        CUSTOMER, HOLD, 24900, "ZAR", "YOCO", "PENDING", null, OffsetDateTime.now())));

    mvc.perform(get("/v1/payments/{id}", PAYMENT)).andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("customer access denied"));
  }
}
