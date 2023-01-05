## Installation

```
implementation 'com.github.spressoinsights:spresso-sdk-android:0.3.8'
```

## Initialization

Initialize the library with your Org ID and if it's in debug mode.

```
Spresso mSpresso = Spresso.getInstance(context, "<org_id>, "<is_debug_boolean>");
```

## Setting a User

If a user is available, you can specify it here

```
mSpresso.identify("<user_id>");
```

## Tracking Events

Example of sending data when a user views a product

```
HashMap<String, Object> props = new HashMap<String, Object>();
props.put("variantSku", "<variant_sku>");
props.put("variantName", "<variant_name>");
props.put("variantPrice", "<variant_price>");
JSONObject properties = new JSONObject(props);
mSpresso.track(Spresso.SPRESSO_EVENT_VIEW_PRODUCT, properties);
```
