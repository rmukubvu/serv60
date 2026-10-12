import 'dart:convert';

import 'package:http/http.dart' as http;

import '../auth/access_token_provider.dart';

class PaymentOrder {
  const PaymentOrder({
    required this.id,
    required this.customerId,
    required this.holdId,
    required this.amountMinor,
    required this.currency,
    required this.provider,
    required this.status,
    this.providerReference,
    required this.createdAt,
  });

  final String id;
  final String customerId;
  final String holdId;
  final int amountMinor;
  final String currency;
  final String provider;
  final String status;
  final String? providerReference;
  final DateTime createdAt;

  factory PaymentOrder.fromJson(Map<String, dynamic> json) => PaymentOrder(
        id: json['id'] as String,
        customerId: json['customerId'] as String,
        holdId: json['holdId'] as String,
        amountMinor: json['amountMinor'] as int,
        currency: json['currency'] as String,
        provider: json['provider'] as String,
        status: json['status'] as String,
        providerReference: json['providerReference'] as String?,
        createdAt: DateTime.parse(json['createdAt'] as String),
      );
}

class PaymentRepository {
  PaymentRepository({
    http.Client? client,
    AccessTokenProvider? tokenProvider,
    this.baseUrl = const String.fromEnvironment('SERV60_API_BASE_URL',
        defaultValue: 'http://10.0.2.2:8080'),
    this.customerId = const String.fromEnvironment('SERV60_CUSTOMER_ID',
        defaultValue: '00000000-0000-0000-0000-000000000001'),
  })  : client = client ?? http.Client(),
        tokenProvider = tokenProvider ?? NoopAccessTokenProvider();

  final http.Client client;
  final AccessTokenProvider tokenProvider;
  final String baseUrl;
  final String customerId;

  Future<PaymentOrder> createOrder({
    required String holdId,
    required int amountMinor,
    required String provider,
    String currency = 'ZAR',
    String? idempotencyKey,
  }) async {
    final key =
        idempotencyKey ?? 'mobile-payment-$holdId-$amountMinor-$provider';
    final response = await client.post(
      Uri.parse('$baseUrl/v1/payments'),
      headers: await _headers({
        'Content-Type': 'application/json',
        'Idempotency-Key': key,
      }),
      body: jsonEncode({
        'customerId': customerId,
        'holdId': holdId,
        'amountMinor': amountMinor,
        'currency': currency,
        'provider': provider,
      }),
    );
    if (response.statusCode >= 200 && response.statusCode < 300) {
      return PaymentOrder.fromJson(
          jsonDecode(response.body) as Map<String, dynamic>);
    }
    throw Exception('We could not start the payment yet.');
  }

  Future<PaymentOrder?> findOrder(String id) async {
    final response = await client.get(
      Uri.parse('$baseUrl/v1/payments/$id'),
      headers: await _headers(),
    );
    if (response.statusCode == 404) return null;
    if (response.statusCode >= 200 && response.statusCode < 300) {
      return PaymentOrder.fromJson(
          jsonDecode(response.body) as Map<String, dynamic>);
    }
    throw Exception('We could not refresh the payment status.');
  }

  Future<Map<String, String>> _headers(
      [Map<String, String> headers = const {}]) async {
    final token = await tokenProvider.accessToken();
    if (token == null || token.trim().isEmpty) return headers;
    return {...headers, 'Authorization': 'Bearer ${token.trim()}'};
  }
}
