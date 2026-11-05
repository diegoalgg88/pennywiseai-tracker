import os
import re

base = r"C:\Users\DiegoGzz\Documents\Programas\My-Projects\PennyWiseAI-tracker\app\src\main\res"

locales = [
    "values-ar-rSA",
    "values-be-rBY", 
    "values-cs-rCZ",
    "values-de-rDE",
    "values-es",
    "values-fr-rFR",
    "values-hi-rIN",
    "values-ne-rNP",
    "values-pt-rBR",
    "values-pt-rPT",
    "values-ru-rRU",
    "values-sw-rKE",
    "values-th-rTH",
]

old_pattern = r'<plurals name="chat_stats_model_messages">\s*<item quantity="one">[^<]*</item>\s*<item quantity="other">[^<]*</item>\s*</plurals>'
new_plural = '''    <plurals name="chat_stats_model_messages">
        <item quantity="one">%2$s • %1$d message</item>
        <item quantity="other">%2$s • %1$d messages</item>
    </plurals>'''

for locale in locales:
    path = os.path.join(base, locale, "strings_rules.xml")
    if not os.path.exists(path):
        print(f"NOT FOUND: {path}")
        continue
    
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    new_content = re.sub(old_pattern, new_plural, content, flags=re.DOTALL)
    
    if new_content == content:
        print(f"NO CHANGE (pattern not found): {path}")
    else:
        with open(path, 'w', encoding='utf-8') as f:
            f.write(new_content)
        print(f"UPDATED: {path}")

print("Done")