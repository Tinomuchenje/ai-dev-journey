# AI Dev Journey

This project is a tutorial to assist developers in working with AI, specifically using LangChain and OpenAI.

## Setup

1. Ensure you have Node.js installed on your system.
2. Clone this repository:
   ```
   git clone https://github.com/your-username/ai-dev-journey.git
   cd ai-dev-journey
   ```
3. Install the necessary dependencies:
   ```
   npm install
   ```
4. Create a `.env` file in the project root and add your OpenAI API key:
   ```
   OPENAI_API_KEY=your_api_key_here
   ```

## Running the Project

To run any TypeScript file in this project:
```
npm run dev -- <filename>.ts
```
Replace `<filename>.ts` with the name of the file you want to run.

## Project Structure

- `part1.ts`: This file contains the code for the first part of the tutorial.



## Part 1: Introduction to ChatOpenAI

File: `part1.ts`

This script demonstrates how to use the ChatOpenAI model from LangChain to interact with the OpenAI API.

### What it does:
1. Imports the ChatOpenAI class from LangChain.
2. Creates an instance of ChatOpenAI.
3. Invokes the model with a question about Hello World.
4. Logs the response to the console.

### To run:
```
npm run dev -- part1.ts
```

### Expected output:
The script will output the AI's response to the question "What is Hello World".

## Troubleshooting

If you encounter any issues:
1. Ensure your OpenAI API key is correctly set in the `.env` file.
2. Check that all dependencies are installed correctly.
3. Make sure you're using a compatible version of Node.js.

## Contributing

Feel free to submit pull requests or create issues if you have suggestions for improvements or encounter any problems.

## License

[MIT License](LICENSE)
