"""Sums the JUnit XML reports of the last unit-test run and prints every failure."""
import glob
import re

total = failed = errors = 0
for path in glob.glob('app/build/test-results/testDebugUnitTest/*.xml'):
    text = open(path, encoding='utf-8').read()
    head = re.search(r'tests="(\d+)".*?failures="(\d+)".*?errors="(\d+)"', text)
    total += int(head.group(1))
    failed += int(head.group(2))
    errors += int(head.group(3))
    for case in re.finditer(r'<testcase name="([^"]+)"[^>]*>\s*<(?:failure|error) message="([^"]{0,300})', text):
        print(path.split('TEST-')[-1], case.group(1), '=>', case.group(2))
print('tests', total, 'failures', failed, 'errors', errors)
