# Mobile OAuth and payment return links

The app uses two browser-based return paths:

- `serv60://oauth/callback` for Cognito Authorization Code + PKCE. The
  `flutter_appauth` plugin consumes this callback during sign-in.
- `serv60://payment-return` for the Yoco success and cancel URLs. The app
  stores the pending payment order in secure storage, then refreshes the
  provider-neutral payment order when this link is opened. The `status` query
  parameter is informational; the signed Yoco webhook remains authoritative.

The repository currently contains the Flutter domain and plugin adapters but
does not generate platform folders. When creating the iOS and Android shells,
register the scheme as follows:

## Android

Add an intent filter to the activity in `android/app/src/main/AndroidManifest.xml`:

```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="serv60" android:host="oauth" />
    <data android:scheme="serv60" android:host="payment-return" />
</intent-filter>
```

## iOS

Register `serv60` under `CFBundleURLTypes` in `ios/Runner/Info.plist`. The
AppAuth iOS setup must also include the redirect scheme expected by the
`flutter_appauth` version selected by the generated shell.

Configure the same URLs in Cognito and Yoco:

```text
SERV60_COGNITO_REDIRECT_URL=serv60://oauth/callback
SERV60_COGNITO_LOGOUT_REDIRECT_URL=serv60://oauth/logout
SERV60_PAYMENT_RETURN_SCHEME=serv60
SERV60_PAYMENT_RETURN_HOST=payment-return
SERV60_YOCO_SUCCESS_URL=serv60://payment-return?status=success
SERV60_YOCO_CANCEL_URL=serv60://payment-return?status=cancelled
```

The mobile client must never receive a Cognito client secret or a Yoco secret
key. Both providers use public redirect configuration on the client and keep
token/provider secrets on their respective server boundaries.
