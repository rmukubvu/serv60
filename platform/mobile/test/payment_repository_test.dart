import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'package:serv60_mobile/src/auth/access_token_provider.dart';
import 'package:serv60_mobile/src/payment/payment_repository.dart';

const paymentId = '00000000-0000-0000-0000-000000000098';
const paymentHoldId = '00000000-0000-0000-0000-000000000099';

Map<String, dynamic> paymentJson() => {
      'id': paymentId,
      'customerId': 'customer-1',
      'holdId': paymentHoldId,
      'amountMinor': 24900,
      'currency': 'ZAR',
      'provider': 'YOCO',
      'status': 'PENDING',
      'providerReference': null,
      'checkoutUrl': null,
      'createdAt': '2026-10-12T08:00:00Z',
    };

void main() {
  test('creates an idempotent payment order with bearer authentication',
      () async {
    late http.Request request;
    final repository = PaymentRepository(
      baseUrl: 'https://api.example.test',
      customerId: 'customer-1',
      tokenProvider: CallbackAccessTokenProvider(() async => ' access-123 '),
      client: MockClient((incoming) async {
        request = incoming;
        return http.Response(jsonEncode(paymentJson()), 201);
      }),
    );

    final order = await repository.createOrder(
      holdId: paymentHoldId,
      amountMinor: 24900,
      provider: 'YOCO',
      idempotencyKey: 'payment-key',
    );

    expect(request.method, 'POST');
    expect(request.url.path, '/v1/payments');
    expect(request.headers['authorization'], 'Bearer access-123');
    expect(request.headers['idempotency-key'], 'payment-key');
    final body = jsonDecode(request.body) as Map<String, dynamic>;
    expect(body['customerId'], 'customer-1');
    expect(body['holdId'], paymentHoldId);
    expect(body['amountMinor'], 24900);
    expect(order.id, paymentId);
    expect(order.status, 'PENDING');
  });

  test('starts hosted checkout and returns its redirect URL', () async {
    late http.Request request;
    final repository = PaymentRepository(
      baseUrl: 'https://api.example.test',
      tokenProvider: CallbackAccessTokenProvider(() async => 'access-123'),
      client: MockClient((incoming) async {
        request = incoming;
        return http.Response(
            jsonEncode({
              ...paymentJson(),
              'providerReference': 'ch_123',
              'checkoutUrl': 'https://checkout.yoco.test/ch_123',
            }),
            200);
      }),
    );

    final order = await repository.startCheckout(paymentId,
        idempotencyKey: 'checkout-key');

    expect(request.method, 'POST');
    expect(request.url.path, '/v1/payments/$paymentId/checkout');
    expect(request.headers['authorization'], 'Bearer access-123');
    expect(request.headers['idempotency-key'], 'checkout-key');
    expect(order.checkoutUrl, 'https://checkout.yoco.test/ch_123');
  });

  test('uses a deterministic checkout idempotency key when omitted', () async {
    late http.Request request;
    final repository = PaymentRepository(
      client: MockClient((incoming) async {
        request = incoming;
        return http.Response(jsonEncode(paymentJson()), 200);
      }),
    );

    await repository.startCheckout(paymentId);

    expect(request.headers['idempotency-key'], 'mobile-checkout-$paymentId');
  });

  test('uses a deterministic idempotency key when omitted', () async {
    late http.Request request;
    final repository = PaymentRepository(
      client: MockClient((incoming) async {
        request = incoming;
        return http.Response(jsonEncode(paymentJson()), 201);
      }),
    );

    await repository.createOrder(
        holdId: paymentHoldId, amountMinor: 24900, provider: 'YOCO');

    expect(request.headers['idempotency-key'],
        'mobile-payment-$paymentHoldId-24900-YOCO');
  });

  test('finds an order and returns null for a missing order', () async {
    var calls = 0;
    final repository = PaymentRepository(
      client: MockClient((request) async {
        calls++;
        expect(request.method, 'GET');
        return calls == 1
            ? http.Response(jsonEncode(paymentJson()), 200)
            : http.Response('', 404);
      }),
    );

    expect((await repository.findOrder(paymentId))!.provider, 'YOCO');
    expect(await repository.findOrder(paymentId), isNull);
  });

  test('raises a stable error when the payment command fails', () async {
    final repository = PaymentRepository(
      client: MockClient((_) async => http.Response('conflict', 409)),
    );

    expect(
      () => repository.createOrder(
          holdId: paymentHoldId, amountMinor: 24900, provider: 'YOCO'),
      throwsA(isA<Exception>()),
    );
  });
}
