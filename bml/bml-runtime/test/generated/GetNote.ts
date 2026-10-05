// Generated GraphQL operation GetNote. Do not edit.
import { bosca } from "../../src/graphql"

export type Visibility = "PUBLIC" | "PRIVATE"

export interface GetNoteVariables {
  id: string
}

export interface GetNoteDataNote {
  id: string
  visibility: Visibility | null
}

export interface GetNoteData {
  note: GetNoteDataNote | null
}

export const GetNote = {
  operationName: "GetNote" as const,
  query: "query GetNote($id: ID!) { note(id: $id) { id visibility } }",
}

export function getNote(variables: GetNoteVariables): Promise<GetNoteData> {
  return bosca.query<GetNoteData, GetNoteVariables>({ query: GetNote.query, operationName: GetNote.operationName, variables })
}
