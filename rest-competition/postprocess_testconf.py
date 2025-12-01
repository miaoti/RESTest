import yaml
import argparse
import os

def main():
    parser = argparse.ArgumentParser(description="Process a YAML test configuration file and create a modified version.")
    parser.add_argument('input_file', help='Path to the input YAML file (e.g., testConf.yaml)')
    args = parser.parse_args()

    # Check if input file exists
    if not os.path.exists(args.input_file):
        print(f"Error: Input file '{args.input_file}' does not exist.")
        return

    # Load the YAML file
    with open(args.input_file, 'r') as file:
        data = yaml.safe_load(file)

    # Modification logic: Replace generators where type is not "RandomBoolean"
    for operation in data['testConfiguration']['operations']:
        if 'testParameters' not in operation or not operation['testParameters']:
            continue
        method = operation['method'].lower()
        test_path = operation['testPath'].replace('/', '_').replace('{', '_').replace('}', '_')

        # Case 1: Add stateful generator for first parameter if method is put, patch, or delete
        if method in ['put', 'patch', 'delete'] and operation['testParameters']:
            first_param = operation['testParameters'][0]
            add_parameter_generator_if_not_present(first_param)
        for param in operation['testParameters']:
            param_name = param['name']
            param_in = param['in']

            # Case 2: Replace generators that are not RandomBoolean nor ParameterGenerator nor in body parameters
            if param_in != 'body':
                csv_path = f"tool/LLM_RT/{method}_{test_path}_{param_name}.csv"
                new_generator = {
                    "type": "RandomInputValue",
                    "genParameters": [
                        {
                            "name": "csv",
                            "values": [csv_path]
                        }
                    ],
                    "valid": True
                }
                # Replace each generator that is not RandomBoolean
                param['generators'] = [
                    new_generator if gen['type'] not in ['RandomBoolean', 'ParameterGenerator'] else gen
                    for gen in param['generators']
                ]

            # # Case 3: Add stateful generator for path parameters
            # if param_in == 'path':
            #     add_parameter_generator_if_not_present(param)
            
            # Case 4: Add stateful generator for parameters with name containing "id" or "code"
            if 'id' in param_name.lower() or 'code' in param_name.lower():
                add_parameter_generator_if_not_present(param)

            # Case 5: Replace generators of body parameters with ObjectPerturbator
            if param_in == 'body':
                json_path = f"tool/LLM_RT/{method}_{test_path}_body.json"
                new_generator = {
                    "type": "ObjectPerturbator",
                    "genParameters": [
                        {
                            "name": "file",
                            "values": [json_path]
                        }
                    ],
                    "valid": True
                }
                param['generators'] = [new_generator]            

    # Write the modified data to modifiedTestConf.yaml
    output_file = f"modified_{os.path.basename(args.input_file)}"
    with open(output_file, 'w') as file:
        yaml.dump(data, file, default_flow_style=False)

    print(f"Modified YAML saved to {output_file}")


def add_parameter_generator_if_not_present(param):
    if not any(gen['type'] == "ParameterGenerator" for gen in param['generators']):
        param['generators'].append({
            "type": "ParameterGenerator",
            "genParameters": [],
            "valid": True
        })


if __name__ == "__main__":
    main()