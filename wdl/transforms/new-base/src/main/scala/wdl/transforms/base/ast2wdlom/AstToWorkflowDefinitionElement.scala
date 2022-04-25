package wdl.transforms.base.ast2wdlom

import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.validated._
import common.collections.EnhancedCollections._
import common.transforms.CheckedAtoB
import common.validation.ErrorOr._
import wdl.model.draft3.elements._
import wom.SourceFileLocation
import wdl.model.draft3.elements.ExpressionElement._

object AstToWorkflowDefinitionElement {

  def astToWorkflowDefinitionElement(implicit astNodeToWorkflowBodyElement: CheckedAtoB[GenericAstNode, WorkflowBodyElement]
                                    ): CheckedAtoB[GenericAst, WorkflowDefinitionElement] = CheckedAtoB.fromErrorOr { a: GenericAst =>
    val nameElementValidation: ErrorOr[String] = astNodeToString(a.getAttribute("name")).toValidated

    val sourceLocation : Option[SourceFileLocation] = a.getSourceLine.map(SourceFileLocation(_))
    val bodyElementsValidation: ErrorOr[Vector[WorkflowBodyElement]] = a.getAttributeAsVector[WorkflowBodyElement]("body")(astNodeToWorkflowBodyElement).toValidated
    (nameElementValidation, bodyElementsValidation) flatMapN { (name, bodyElements) =>
      combineElements(name, sourceLocation, bodyElements)
    }
  }

  private def combineElements(name: String,
                              sourceLocation: Option[SourceFileLocation],
                              bodyElements: Vector[WorkflowBodyElement]) = {

    val inputsSectionValidation: ErrorOr[Option[InputsSectionElement]] = for {
      inputValidateElement <- validateSize(bodyElements.filterByType[InputsSectionElement], "inputs", 1): ErrorOr[Option[InputsSectionElement]]
      _ <- checkIfStdInputExist(inputValidateElement, StdoutElement, "stdout")
      _ <- checkIfStdInputExist(inputValidateElement, StderrElement, "stderr")
    } yield inputValidateElement

    val intermediateValueDeclarationStdoutCheck: ErrorOr[Option[String]] = checkStdIntermediates(bodyElements.filterByType[IntermediateValueDeclarationElement], StdoutElement, "stdout")
    val intermediateValueDeclarationStderrCheck: ErrorOr[Option[String]] = checkStdIntermediates(bodyElements.filterByType[IntermediateValueDeclarationElement], StderrElement, "stderr")

    val outputsSectionValidation: ErrorOr[Option[OutputsSectionElement]] = for {
      outputValidateElement <- validateSize(bodyElements.filterByType[OutputsSectionElement], "outputs", 1): ErrorOr[Option[OutputsSectionElement]]
      _ <- checkIfStdOutputExists(outputValidateElement, StdoutElement, "stdout")
      _ <- checkIfStdOutputExists(outputValidateElement, StderrElement, "stderr")
    } yield outputValidateElement

    val graphSections: Vector[WorkflowGraphElement] = bodyElements.filterByType[WorkflowGraphElement]

    val metaSectionValidation: ErrorOr[Option[MetaSectionElement]] = validateSize(bodyElements.filterByType[MetaSectionElement], "meta", 1)
    val parameterMetaSectionValidation: ErrorOr[Option[ParameterMetaSectionElement]] = validateSize(bodyElements.filterByType[ParameterMetaSectionElement], "parameterMeta", 1)

    (inputsSectionValidation, outputsSectionValidation, metaSectionValidation, parameterMetaSectionValidation, intermediateValueDeclarationStdoutCheck, intermediateValueDeclarationStderrCheck) mapN {
      (validInputs, validOutputs, meta, parameterMeta, _, _) =>
      WorkflowDefinitionElement(name, validInputs, graphSections.toSet, validOutputs, meta, parameterMeta, sourceLocation)
    }
  }

  /*private def check[A <: WorkflowGraphElement, B](section: Seq[A], expressionType: B, expressionName: String): ErrorOr[Option[String]] = {
    if (section.map(_.expression).exists(_.isInstanceOf[expressionType.type])) {
      s"Workflow cannot have $expressionName expression at workflow-level.".invalidNel
    } else None.validNel
  }
  */

  private def checkIfStdInputExist(inputSection: Option[InputsSectionElement], expressionType: FunctionCallElement, expressionName: String): ErrorOr[Option[String]] = {
    inputSection match {
      case Some(section) =>
        if (section.inputDeclarations.flatMap(_.expression).exists(_.isInstanceOf[expressionType.type])) {
          s"Workflow cannot have $expressionName expression in input section at workflow-level.".invalidNel
        } else None.validNel
      case None => None.validNel
    }
  }

  private def checkIfStdOutputExists[A](outputSection: Option[OutputsSectionElement], expressionType: FunctionCallElement, expressionName: String): ErrorOr[Option[String]] = {
    outputSection match {
      case Some(section) =>
        if (section.outputs.map(_.expression).exists(_.isInstanceOf[expressionType.type])) {
          s"Workflow cannot have $expressionName expression in output section at workflow-level.".invalidNel
        } else None.validNel
      case None => None.validNel
    }
  }

  private def checkStdIntermediates[A](intermediate: Vector[IntermediateValueDeclarationElement], expressionType: FunctionCallElement, expressionName: String): ErrorOr[Option[String]] = {
    if (intermediate.map(_.expression).exists(_.isInstanceOf[expressionType.type])) {
      s"Workflow cannot have $expressionName expression at intermediate declaration section at workflow-level.".invalidNel
    } else None.validNel
  }

  private def validateSize[A](elements: Vector[A], sectionName: String, numExpected: Int): ErrorOr[Option[A]] = {
    val sectionValidation: ErrorOr[Option[A]] = if (elements.size > numExpected) {
      s"Workflow cannot have more than $numExpected $sectionName sections, found ${elements.size}.".invalidNel
    } else {
      elements.headOption.validNel
    }

    sectionValidation
  }
}
