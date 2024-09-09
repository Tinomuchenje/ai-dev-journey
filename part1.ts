import { ChatOpenAI } from "@langchain/openai";
import dotenv from 'dotenv';
dotenv.config();

async function main() {
  const chatModel = new ChatOpenAI({ model: "gpt-4" });

  const response = await chatModel.invoke("What is Hello World?");
  console.log(response);
}

main().catch(console.error);