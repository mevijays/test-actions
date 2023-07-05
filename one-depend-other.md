# Workflow 1:
```yaml
name: Workflow 1
on: [push]
jobs:
  workflow1_job:
    runs-on: ubuntu-latest
    steps:
    - name: Set Output Variable
      run: echo "::set-output name=workflow1_output::Hello World"
```
# Workflow 2:
```yaml
name: Workflow 2
on:
  workflow1_job:
    type: [completed]
jobs:
  workflow2_job:
    runs-on: ubuntu-latest
    steps:
    - name: Get Output Variable
      run: echo "${{ steps.workflow1_job.outputs.workflow1_output }}"
```
