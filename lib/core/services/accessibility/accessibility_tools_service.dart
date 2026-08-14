import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// 无障碍服务工具
///
/// 通过 MethodChannel 调用 Android 无障碍服务，实现：
/// - 读取屏幕内容
/// - 点击元素
/// - 输入文字
/// - 滑动
/// - 按键
/// - 打开应用
class AccessibilityTools {
  static const MethodChannel _channel = MethodChannel('app.accessibility');

  static bool get supported =>
      !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  /// 检查无障碍服务是否已开启
  static Future<bool> isEnabled() async {
    if (!supported) return false;
    try {
      final result = await _channel.invokeMethod<bool>('isEnabled');
      return result == true;
    } catch (e) {
      return false;
    }
  }

  /// 读取当前屏幕内容
  static Future<Map<String, dynamic>> readScreen({String format = 'tree'}) async {
    final result = await _channel.invokeMethod<String>('readScreen', {'format': format});
    return _parseResult(result);
  }

  /// 查找并点击元素
  static Future<Map<String, dynamic>> findAndClick({
    String? text,
    String? id,
    String? description,
    int index = 0,
  }) async {
    final args = <String, dynamic>{};
    if (text != null) args['text'] = text;
    if (id != null) args['id'] = id;
    if (description != null) args['description'] = description;
    args['index'] = index;

    final result = await _channel.invokeMethod<String>('findAndClick', jsonEncode(args));
    return _parseResult(result);
  }

  /// 查找输入框并输入文字
  static Future<Map<String, dynamic>> findAndInput({
    required String text,
    String? target,
    String? targetId,
  }) async {
    final args = <String, dynamic>{'text': text};
    if (target != null) args['target'] = target;
    if (targetId != null) args['target_id'] = targetId;

    final result = await _channel.invokeMethod<String>('findAndInput', jsonEncode(args));
    return _parseResult(result);
  }

  /// 模拟滑动
  static Future<Map<String, dynamic>> swipe({required String direction}) async {
    final result = await _channel.invokeMethod<String>('swipe', jsonEncode({'direction': direction}));
    return _parseResult(result);
  }

  /// 模拟按键
  static Future<Map<String, dynamic>> pressButton({required String button}) async {
    final result = await _channel.invokeMethod<String>('pressButton', jsonEncode({'button': button}));
    return _parseResult(result);
  }

  /// 打开应用
  static Future<Map<String, dynamic>> openApp({
    String? packageName,
    String? appName,
  }) async {
    final args = <String, dynamic>{};
    if (packageName != null) args['package_name'] = packageName;
    if (appName != null) args['app_name'] = appName;

    final result = await _channel.invokeMethod<String>('openApp', jsonEncode(args));
    return _parseResult(result);
  }

  /// 截图
  static Future<Map<String, dynamic>> takeScreenshot() async {
    final result = await _channel.invokeMethod<String>('takeScreenshot');
    return _parseResult(result);
  }

  static Map<String, dynamic> _parseResult(String? result) {
    if (result == null || result.isEmpty) {
      return {'error': 'no_result', 'message': 'No result from accessibility service'};
    }
    try {
      return jsonDecode(result) as Map<String, dynamic>;
    } catch (e) {
      return {'error': 'parse_error', 'message': result};
    }
  }
}

/// 无障碍工具名称常量
class AccessibilityToolNames {
  static const String readScreen = 'read_screen';
  static const String clickElement = 'click_element';
  static const String inputText = 'input_text';
  static const String swipeScreen = 'swipe_screen';
  static const String pressButton = 'press_button';
  static const String openApp = 'open_app';
  static const String takeScreenshot = 'take_screenshot';
}

/// 无障碍工具定义
class AccessibilityToolDefinitions {
  static List<Map<String, dynamic>> buildDefinitions({required bool enabled}) {
    if (!enabled || !AccessibilityTools.supported) return [];

    return [
      {
        'type': 'function',
        'function': {
          'name': AccessibilityToolNames.readScreen,
          'description':
              "Read the current screen content as a tree of UI elements. "
              "Returns the element hierarchy with text, descriptions, bounds, and properties. "
              "Use this to understand what's currently displayed on screen before interacting with it.",
          'parameters': {
            'type': 'object',
            'properties': {
              'format': {
                'type': 'string',
                'enum': ['tree', 'flat'],
                'description':
                    'Output format: "tree" for hierarchical view, "flat" for a list of elements. Default "tree".',
              },
            },
          },
        },
      },
      {
        'type': 'function',
        'function': {
          'name': AccessibilityToolNames.clickElement,
          'description':
              "Click/tap a UI element on screen. "
              "Specify the element by text content, resource ID, or content description. "
              "Use read_screen first to find the correct element.",
          'parameters': {
            'type': 'object',
            'properties': {
              'text': {
                'type': 'string',
                'description': 'Text content of the element to click.',
              },
              'id': {
                'type': 'string',
                'description': 'Resource ID of the element to click.',
              },
              'description': {
                'type': 'string',
                'description': 'Content description of the element to click.',
              },
              'index': {
                'type': 'integer',
                'description': 'Which matching element to click (0-based). Default 0 (first match).',
              },
            },
          },
        },
      },
      {
        'type': 'function',
        'function': {
          'name': AccessibilityToolNames.inputText,
          'description':
              "Type text into an input field on screen. "
              "You can specify which input field by text/ID, or leave it empty to type into the currently focused field.",
          'parameters': {
            'type': 'object',
            'properties': {
              'text': {
                'type': 'string',
                'description': 'The text to type.',
              },
              'target': {
                'type': 'string',
                'description': 'Text content of the input field to type into (optional).',
              },
              'target_id': {
                'type': 'string',
                'description': 'Resource ID of the input field (optional).',
              },
            },
            'required': ['text'],
          },
        },
      },
      {
        'type': 'function',
        'function': {
          'name': AccessibilityToolNames.swipeScreen,
          'description': "Swipe on the screen in a specified direction.",
          'parameters': {
            'type': 'object',
            'properties': {
              'direction': {
                'type': 'string',
                'enum': ['up', 'down', 'left', 'right'],
                'description': 'Direction to swipe.',
              },
            },
            'required': ['direction'],
          },
        },
      },
      {
        'type': 'function',
        'function': {
          'name': AccessibilityToolNames.pressButton,
          'description': "Press a system button (back, home, recent apps, notifications).",
          'parameters': {
            'type': 'object',
            'properties': {
              'button': {
                'type': 'string',
                'enum': ['back', 'home', 'recent', 'notifications'],
                'description': 'Which button to press.',
              },
            },
            'required': ['button'],
          },
        },
      },
      {
        'type': 'function',
        'function': {
          'name': AccessibilityToolNames.openApp,
          'description':
              "Open/launch an application on the device. "
              "Specify either the package name (e.g. 'com.tencent.mm') or the app name (e.g. '微信').",
          'parameters': {
            'type': 'object',
            'properties': {
              'package_name': {
                'type': 'string',
                'description': 'Android package name of the app to open.',
              },
              'app_name': {
                'type': 'string',
                'description': 'Display name of the app to open (will search installed apps).',
              },
            },
          },
        },
      },
    ];
  }
}
