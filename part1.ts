import { ChatOpenAI } from "@langchain/openai";

async function main() {
  const chatModel = new ChatOpenAI({});

  const response = await chatModel.invoke("What is Hello World?");
  console.log(response);
}

main().catch(console.error);