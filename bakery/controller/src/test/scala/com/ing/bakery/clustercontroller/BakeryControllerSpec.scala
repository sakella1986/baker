package com.ing.bakery.clustercontroller

import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import cats.effect.{IO, Resource}
import com.ing.baker.runtime.scaladsl.InteractionInstance
import com.ing.baker.types.CharArray
import com.ing.bakery.clustercontroller.BakeryControllerSpec._
import com.ing.bakery.clustercontroller.controllers.BakerResource.SidecarSpec
import com.ing.bakery.clustercontroller.controllers.ComponentConfigController.ConfigMapDeploymentRelationCache
import com.ing.bakery.clustercontroller.controllers.{BakerController, BakerResource, InteractionController, InteractionResource}
import com.ing.bakery.mocks.WatchEvent.ResourcePath
import com.ing.bakery.mocks.{KubeApiServer, RemoteInteraction}
import com.ing.bakery.testing.BakeryFunSpec
import com.typesafe.config.ConfigFactory
import org.mockserver.integration.ClientAndServer
import org.scalatest.ConfigMap
import org.scalatest.matchers.should.Matchers
import skuber.api.client.KubernetesClient
import skuber.json.format.configMapFmt
import skuber.{EnvVar, ObjectMeta}

import scala.concurrent.Future

object BakeryControllerSpec {

  val interactionResource: InteractionResource = InteractionResource(
    metadata = ObjectMeta(name = "localhost"),
    spec = InteractionResource.Spec(
      image = "interaction.image:1.0.0",
      imagePullSecret = None,
      replicas = 2,
      env = List(
        EnvVar("ONE", EnvVar.StringValue("one")),
        EnvVar("TWO", EnvVar.ConfigMapKeyRef(name = "my-config-map", key = "two")),
        EnvVar("THREE", EnvVar.SecretKeyRef(name = "my-secret", key = "three"))
      ),
      configMapMounts = Some(List("my-config-map")),
      secretMounts = Some(List("my-secret")),
      resources = Some(skuber.Resource.Requirements(
        requests = Map("cpu" -> skuber.Resource.Quantity("600m"), "memory" -> skuber.Resource.Quantity("500Mi")),
        limits = Map("cpu" -> skuber.Resource.Quantity("6000m"), "memory" -> skuber.Resource.Quantity("1000Mi"))
      ))
    )
  )

  val interactionConfigMapResource: skuber.ConfigMap = skuber.ConfigMap(
    metadata = ObjectMeta(name = "localhost"),
    data = Map(
      "image" -> "interaction-make-payment-and-ship-items:1.0.0",
      "replicas" -> "2",
      "env.0.name" -> "ONE",
      "env.0.value" -> "1",
      "env.1.name" -> "TWO",
      "env.1.valueFrom.configMapKeyRef.name" -> "test-config",
      "env.1.valueFrom.configMapKeyRef.key" -> "ONE",
      "env.2.name" -> "THREE",
      "env.2.valueFrom.secretKeyRef.name" -> "test-secret",
      "env.2.valueFrom.secretKeyRef.key" -> "username",
      "configMapMounts.0" -> "test-config",
      "secretMounts.0" -> "test-secret",
      "resources.requests.cpu" -> "600m",
      "resources.requests.memory" -> "500Mi",
      "resources.limits.cpu" -> "6000m",
      "resources.limits.memory" -> "1000Mi"
    )
  )

  val interaction: InteractionInstance = InteractionInstance(
    name = "interaction-one",
    input = Seq(CharArray),
    run = _ => Future.successful(None)
  )

  val bakerResource: BakerResource = BakerResource(
    metadata = ObjectMeta(name = "RecipeOne"),
    spec = BakerResource.Spec(
      image = "bakery-baker:local",
      imagePullSecret = None,
      serviceAccountSecret = None,
      kafkaBootstrapServers = None,
      replicas = 2,
      recipes = List("CgdXZWJzaG9wErEQChYKFAoQdW5hdmFpbGFibGVJdGVtcxABCkVaQwo/ChdTaGlwcGluZ0FkZHJlc3NSZWNlaXZlZBIkCg9zaGlwcGluZ0FkZHJlc3MSESIPCg0KB2FkZHJlc3MSAggREAEKEwoRCg1yZXNlcnZlZEl0ZW1zEAEKCwoJCgVpdGVtcxABCg8KDQoJU2hpcEl0ZW1zEAIKnANimQMKRAoYT3JkZXJIYWRVbmF2YWlsYWJsZUl0ZW1zEigKEHVuYXZhaWxhYmxlSXRlbXMSFBoSChAiDgoMCgZpdGVtSWQSAggRCk8KDUl0ZW1zUmVzZXJ2ZWQSPgoNcmVzZXJ2ZWRJdGVtcxItIisKHQoFaXRlbXMSFBoSChAiDgoMCgZpdGVtSWQSAggRCgoKBGRhdGESAggWEkQKGE9yZGVySGFkVW5hdmFpbGFibGVJdGVtcxIoChB1bmF2YWlsYWJsZUl0ZW1zEhQaEgoQIg4KDAoGaXRlbUlkEgIIERJPCg1JdGVtc1Jlc2VydmVkEj4KDXJlc2VydmVkSXRlbXMSLSIrCh0KBWl0ZW1zEhQaEgoQIg4KDAoGaXRlbUlkEgIIEQoKCgRkYXRhEgIIFiIcCgdvcmRlcklkEhEiDwoNCgdvcmRlcklkEgIIESIdCgVpdGVtcxIUGhIKECIOCgwKBml0ZW1JZBICCBEqDFJlc2VydmVJdGVtczIMUmVzZXJ2ZUl0ZW1zUhAaDgjoBxEAAAAAAAAAQBgFCkhaRgpCChpQYXltZW50SW5mb3JtYXRpb25SZWNlaXZlZBIkChJwYXltZW50SW5mb3JtYXRpb24SDiIMCgoKBGluZm8SAggREAEKFVoTCg8KDVBheW1lbnRGYWlsZWQQAApQWk4KSgoLT3JkZXJQbGFjZWQSHAoHb3JkZXJJZBIRIg8KDQoHb3JkZXJJZBICCBESHQoFaXRlbXMSFBoSChAiDgoMCgZpdGVtSWQSAggREAEKFQoTCg9zaGlwcGluZ0FkZHJlc3MQAQpKWkgKRAoYT3JkZXJIYWRVbmF2YWlsYWJsZUl0ZW1zEigKEHVuYXZhaWxhYmxlSXRlbXMSFBoSChAiDgoMCgZpdGVtSWQSAggREAAK2wNi2AMKcQoRUGF5bWVudFN1Y2Nlc3NmdWwSXAoNc2hpcHBpbmdPcmRlchJLIkkKHQoFaXRlbXMSFBoSChAiDgoMCgZpdGVtSWQSAggRCgoKBGRhdGESAggWChwKB2FkZHJlc3MSESIPCg0KB2FkZHJlc3MSAggRCg8KDVBheW1lbnRGYWlsZWQScQoRUGF5bWVudFN1Y2Nlc3NmdWwSXAoNc2hpcHBpbmdPcmRlchJLIkkKHQoFaXRlbXMSFBoSChAiDgoMCgZpdGVtSWQSAggRCgoKBGRhdGESAggWChwKB2FkZHJlc3MSESIPCg0KB2FkZHJlc3MSAggREg8KDVBheW1lbnRGYWlsZWQiFgoQcmVjaXBlSW5zdGFuY2VJZBICCBEiPgoNcmVzZXJ2ZWRJdGVtcxItIisKHQoFaXRlbXMSFBoSChAiDgoMCgZpdGVtSWQSAggRCgoKBGRhdGESAggWIiQKD3NoaXBwaW5nQWRkcmVzcxIRIg8KDQoHYWRkcmVzcxICCBEiJAoScGF5bWVudEluZm9ybWF0aW9uEg4iDAoKCgRpbmZvEgIIESoLTWFrZVBheW1lbnQyC01ha2VQYXltZW50UhAaDgjoBxEAAAAAAAAAQBgFClVaUwpPCg1JdGVtc1Jlc2VydmVkEj4KDXJlc2VydmVkSXRlbXMSLSIrCh0KBWl0ZW1zEhQaEgoQIg4KDAoGaXRlbUlkEgIIEQoKCgRkYXRhEgIIFhAAChMKEQoNc2hpcHBpbmdPcmRlchABChlaFwoTChFTaGlwcGluZ0NvbmZpcm1lZBAACndadQpxChFQYXltZW50U3VjY2Vzc2Z1bBJcCg1zaGlwcGluZ09yZGVyEksiSQodCgVpdGVtcxIUGhIKECIOCgwKBml0ZW1JZBICCBEKCgoEZGF0YRICCBYKHAoHYWRkcmVzcxIRIg8KDQoHYWRkcmVzcxICCBEQAAoRCg8KC01ha2VQYXltZW50EAIKGAoWChJwYXltZW50SW5mb3JtYXRpb24QAQoSChAKDFJlc2VydmVJdGVtcxACCrMBYrABChMKEVNoaXBwaW5nQ29uZmlybWVkEhMKEVNoaXBwaW5nQ29uZmlybWVkIlwKDXNoaXBwaW5nT3JkZXISSyJJCh0KBWl0ZW1zEhQaEgoQIg4KDAoGaXRlbUlkEgIIEQoKCgRkYXRhEgIIFgocCgdhZGRyZXNzEhEiDwoNCgdhZGRyZXNzEgIIESoJU2hpcEl0ZW1zMglTaGlwSXRlbXNSEBoOCOgHEQAAAAAAAABAGAUKDQoLCgdvcmRlcklkEAESBggLEBAYARIGCBEQCxgBEiAIEhAKGAEiGE9yZGVySGFkVW5hdmFpbGFibGVJdGVtcxIVCBIQDBgBIg1JdGVtc1Jlc2VydmVkEgYIAhALGAESBggGEBEYARIGCAgQFBgBEgYICBADGAESBggBEAkYARIGCAkQCxgBEgYIBRASGAESBggDEAUYARIGCAwQAhgBEgYIChAAGAESBggNEBMYARIZCAQQDhgBIhFTaGlwcGluZ0NvbmZpcm1lZBIGCA8QDRgBEhkIEBAPGAEiEVBheW1lbnRTdWNjZXNzZnVsEhUIEBAHGAEiDVBheW1lbnRGYWlsZWQSBggTEAQYARIGCBQQBRgBOhA5YTJmOGMyODgwZWE4ZmMw"),
      resources = Some(skuber.Resource.Requirements(
        requests = Map("cpu" -> skuber.Resource.Quantity("600m"), "memory" -> skuber.Resource.Quantity("500Mi")),
        limits = Map("cpu" -> skuber.Resource.Quantity("6000m"), "memory" -> skuber.Resource.Quantity("1000Mi"))
      )),
      config = None,
      secrets = None,
      apiLoggingEnabled = true,
      sidecar = None
    )
  )

  val bakerConfigMapResource: skuber.ConfigMap = skuber.ConfigMap(
    metadata = ObjectMeta(name = "RecipeOne"),
    data = Map(
      "image" -> "bakery-baker:local",
      "replicas" -> "2",
      "recipes.0" -> "CgdXZWJzaG9wErEQChYKFAoQdW5hdmFpbGFibGVJdGVtcxABCkVaQwo/ChdTaGlwcGluZ0FkZHJlc3NSZWNlaXZlZBIkCg9zaGlwcGluZ0FkZHJlc3MSESIPCg0KB2FkZHJlc3MSAggREAEKEwoRCg1yZXNlcnZlZEl0ZW1zEAEKCwoJCgVpdGVtcxABCg8KDQoJU2hpcEl0ZW1zEAIKnANimQMKRAoYT3JkZXJIYWRVbmF2YWlsYWJsZUl0ZW1zEigKEHVuYXZhaWxhYmxlSXRlbXMSFBoSChAiDgoMCgZpdGVtSWQSAggRCk8KDUl0ZW1zUmVzZXJ2ZWQSPgoNcmVzZXJ2ZWRJdGVtcxItIisKHQoFaXRlbXMSFBoSChAiDgoMCgZpdGVtSWQSAggRCgoKBGRhdGESAggWEkQKGE9yZGVySGFkVW5hdmFpbGFibGVJdGVtcxIoChB1bmF2YWlsYWJsZUl0ZW1zEhQaEgoQIg4KDAoGaXRlbUlkEgIIERJPCg1JdGVtc1Jlc2VydmVkEj4KDXJlc2VydmVkSXRlbXMSLSIrCh0KBWl0ZW1zEhQaEgoQIg4KDAoGaXRlbUlkEgIIEQoKCgRkYXRhEgIIFiIcCgdvcmRlcklkEhEiDwoNCgdvcmRlcklkEgIIESIdCgVpdGVtcxIUGhIKECIOCgwKBml0ZW1JZBICCBEqDFJlc2VydmVJdGVtczIMUmVzZXJ2ZUl0ZW1zUhAaDgjoBxEAAAAAAAAAQBgFCkhaRgpCChpQYXltZW50SW5mb3JtYXRpb25SZWNlaXZlZBIkChJwYXltZW50SW5mb3JtYXRpb24SDiIMCgoKBGluZm8SAggREAEKFVoTCg8KDVBheW1lbnRGYWlsZWQQAApQWk4KSgoLT3JkZXJQbGFjZWQSHAoHb3JkZXJJZBIRIg8KDQoHb3JkZXJJZBICCBESHQoFaXRlbXMSFBoSChAiDgoMCgZpdGVtSWQSAggREAEKFQoTCg9zaGlwcGluZ0FkZHJlc3MQAQpKWkgKRAoYT3JkZXJIYWRVbmF2YWlsYWJsZUl0ZW1zEigKEHVuYXZhaWxhYmxlSXRlbXMSFBoSChAiDgoMCgZpdGVtSWQSAggREAAK2wNi2AMKcQoRUGF5bWVudFN1Y2Nlc3NmdWwSXAoNc2hpcHBpbmdPcmRlchJLIkkKHQoFaXRlbXMSFBoSChAiDgoMCgZpdGVtSWQSAggRCgoKBGRhdGESAggWChwKB2FkZHJlc3MSESIPCg0KB2FkZHJlc3MSAggRCg8KDVBheW1lbnRGYWlsZWQScQoRUGF5bWVudFN1Y2Nlc3NmdWwSXAoNc2hpcHBpbmdPcmRlchJLIkkKHQoFaXRlbXMSFBoSChAiDgoMCgZpdGVtSWQSAggRCgoKBGRhdGESAggWChwKB2FkZHJlc3MSESIPCg0KB2FkZHJlc3MSAggREg8KDVBheW1lbnRGYWlsZWQiFgoQcmVjaXBlSW5zdGFuY2VJZBICCBEiPgoNcmVzZXJ2ZWRJdGVtcxItIisKHQoFaXRlbXMSFBoSChAiDgoMCgZpdGVtSWQSAggRCgoKBGRhdGESAggWIiQKD3NoaXBwaW5nQWRkcmVzcxIRIg8KDQoHYWRkcmVzcxICCBEiJAoScGF5bWVudEluZm9ybWF0aW9uEg4iDAoKCgRpbmZvEgIIESoLTWFrZVBheW1lbnQyC01ha2VQYXltZW50UhAaDgjoBxEAAAAAAAAAQBgFClVaUwpPCg1JdGVtc1Jlc2VydmVkEj4KDXJlc2VydmVkSXRlbXMSLSIrCh0KBWl0ZW1zEhQaEgoQIg4KDAoGaXRlbUlkEgIIEQoKCgRkYXRhEgIIFhAAChMKEQoNc2hpcHBpbmdPcmRlchABChlaFwoTChFTaGlwcGluZ0NvbmZpcm1lZBAACndadQpxChFQYXltZW50U3VjY2Vzc2Z1bBJcCg1zaGlwcGluZ09yZGVyEksiSQodCgVpdGVtcxIUGhIKECIOCgwKBml0ZW1JZBICCBEKCgoEZGF0YRICCBYKHAoHYWRkcmVzcxIRIg8KDQoHYWRkcmVzcxICCBEQAAoRCg8KC01ha2VQYXltZW50EAIKGAoWChJwYXltZW50SW5mb3JtYXRpb24QAQoSChAKDFJlc2VydmVJdGVtcxACCrMBYrABChMKEVNoaXBwaW5nQ29uZmlybWVkEhMKEVNoaXBwaW5nQ29uZmlybWVkIlwKDXNoaXBwaW5nT3JkZXISSyJJCh0KBWl0ZW1zEhQaEgoQIg4KDAoGaXRlbUlkEgIIEQoKCgRkYXRhEgIIFgocCgdhZGRyZXNzEhEiDwoNCgdhZGRyZXNzEgIIESoJU2hpcEl0ZW1zMglTaGlwSXRlbXNSEBoOCOgHEQAAAAAAAABAGAUKDQoLCgdvcmRlcklkEAESBggLEBAYARIGCBEQCxgBEiAIEhAKGAEiGE9yZGVySGFkVW5hdmFpbGFibGVJdGVtcxIVCBIQDBgBIg1JdGVtc1Jlc2VydmVkEgYIAhALGAESBggGEBEYARIGCAgQFBgBEgYICBADGAESBggBEAkYARIGCAkQCxgBEgYIBRASGAESBggDEAUYARIGCAwQAhgBEgYIChAAGAESBggNEBMYARIZCAQQDhgBIhFTaGlwcGluZ0NvbmZpcm1lZBIGCA8QDRgBEhkIEBAPGAEiEVBheW1lbnRTdWNjZXNzZnVsEhUIEBAHGAEiDVBheW1lbnRGYWlsZWQSBggTEAQYARIGCBQQBRgBOhA5YTJmOGMyODgwZWE4ZmMw",
      "resources.requests.cpu" -> "600m",
      "resources.requests.memory" -> "500Mi",
      "resources.limits.cpu" -> "6000m",
      "resources.limits.memory" -> "1000Mi"
    )
  )

  val bakerResourceSidecar: BakerResource = BakerResource(
    metadata = ObjectMeta(name = "RecipeOne"),
    spec = BakerResource.Spec(
      image = "bakery-baker:local",
      imagePullSecret = None,
      serviceAccountSecret = None,
      kafkaBootstrapServers = None,
      replicas = 2,
      recipes = List("CgdXZWJzaG9wErEQChYKFAoQdW5hdmFpbGFibGVJdGVtcxABCkVaQwo/ChdTaGlwcGluZ0FkZHJlc3NSZWNlaXZlZBIkCg9zaGlwcGluZ0FkZHJlc3MSESIPCg0KB2FkZHJlc3MSAggREAEKEwoRCg1yZXNlcnZlZEl0ZW1zEAEKCwoJCgVpdGVtcxABCg8KDQoJU2hpcEl0ZW1zEAIKnANimQMKRAoYT3JkZXJIYWRVbmF2YWlsYWJsZUl0ZW1zEigKEHVuYXZhaWxhYmxlSXRlbXMSFBoSChAiDgoMCgZpdGVtSWQSAggRCk8KDUl0ZW1zUmVzZXJ2ZWQSPgoNcmVzZXJ2ZWRJdGVtcxItIisKHQoFaXRlbXMSFBoSChAiDgoMCgZpdGVtSWQSAggRCgoKBGRhdGESAggWEkQKGE9yZGVySGFkVW5hdmFpbGFibGVJdGVtcxIoChB1bmF2YWlsYWJsZUl0ZW1zEhQaEgoQIg4KDAoGaXRlbUlkEgIIERJPCg1JdGVtc1Jlc2VydmVkEj4KDXJlc2VydmVkSXRlbXMSLSIrCh0KBWl0ZW1zEhQaEgoQIg4KDAoGaXRlbUlkEgIIEQoKCgRkYXRhEgIIFiIcCgdvcmRlcklkEhEiDwoNCgdvcmRlcklkEgIIESIdCgVpdGVtcxIUGhIKECIOCgwKBml0ZW1JZBICCBEqDFJlc2VydmVJdGVtczIMUmVzZXJ2ZUl0ZW1zUhAaDgjoBxEAAAAAAAAAQBgFCkhaRgpCChpQYXltZW50SW5mb3JtYXRpb25SZWNlaXZlZBIkChJwYXltZW50SW5mb3JtYXRpb24SDiIMCgoKBGluZm8SAggREAEKFVoTCg8KDVBheW1lbnRGYWlsZWQQAApQWk4KSgoLT3JkZXJQbGFjZWQSHAoHb3JkZXJJZBIRIg8KDQoHb3JkZXJJZBICCBESHQoFaXRlbXMSFBoSChAiDgoMCgZpdGVtSWQSAggREAEKFQoTCg9zaGlwcGluZ0FkZHJlc3MQAQpKWkgKRAoYT3JkZXJIYWRVbmF2YWlsYWJsZUl0ZW1zEigKEHVuYXZhaWxhYmxlSXRlbXMSFBoSChAiDgoMCgZpdGVtSWQSAggREAAK2wNi2AMKcQoRUGF5bWVudFN1Y2Nlc3NmdWwSXAoNc2hpcHBpbmdPcmRlchJLIkkKHQoFaXRlbXMSFBoSChAiDgoMCgZpdGVtSWQSAggRCgoKBGRhdGESAggWChwKB2FkZHJlc3MSESIPCg0KB2FkZHJlc3MSAggRCg8KDVBheW1lbnRGYWlsZWQScQoRUGF5bWVudFN1Y2Nlc3NmdWwSXAoNc2hpcHBpbmdPcmRlchJLIkkKHQoFaXRlbXMSFBoSChAiDgoMCgZpdGVtSWQSAggRCgoKBGRhdGESAggWChwKB2FkZHJlc3MSESIPCg0KB2FkZHJlc3MSAggREg8KDVBheW1lbnRGYWlsZWQiFgoQcmVjaXBlSW5zdGFuY2VJZBICCBEiPgoNcmVzZXJ2ZWRJdGVtcxItIisKHQoFaXRlbXMSFBoSChAiDgoMCgZpdGVtSWQSAggRCgoKBGRhdGESAggWIiQKD3NoaXBwaW5nQWRkcmVzcxIRIg8KDQoHYWRkcmVzcxICCBEiJAoScGF5bWVudEluZm9ybWF0aW9uEg4iDAoKCgRpbmZvEgIIESoLTWFrZVBheW1lbnQyC01ha2VQYXltZW50UhAaDgjoBxEAAAAAAAAAQBgFClVaUwpPCg1JdGVtc1Jlc2VydmVkEj4KDXJlc2VydmVkSXRlbXMSLSIrCh0KBWl0ZW1zEhQaEgoQIg4KDAoGaXRlbUlkEgIIEQoKCgRkYXRhEgIIFhAAChMKEQoNc2hpcHBpbmdPcmRlchABChlaFwoTChFTaGlwcGluZ0NvbmZpcm1lZBAACndadQpxChFQYXltZW50U3VjY2Vzc2Z1bBJcCg1zaGlwcGluZ09yZGVyEksiSQodCgVpdGVtcxIUGhIKECIOCgwKBml0ZW1JZBICCBEKCgoEZGF0YRICCBYKHAoHYWRkcmVzcxIRIg8KDQoHYWRkcmVzcxICCBEQAAoRCg8KC01ha2VQYXltZW50EAIKGAoWChJwYXltZW50SW5mb3JtYXRpb24QAQoSChAKDFJlc2VydmVJdGVtcxACCrMBYrABChMKEVNoaXBwaW5nQ29uZmlybWVkEhMKEVNoaXBwaW5nQ29uZmlybWVkIlwKDXNoaXBwaW5nT3JkZXISSyJJCh0KBWl0ZW1zEhQaEgoQIg4KDAoGaXRlbUlkEgIIEQoKCgRkYXRhEgIIFgocCgdhZGRyZXNzEhEiDwoNCgdhZGRyZXNzEgIIESoJU2hpcEl0ZW1zMglTaGlwSXRlbXNSEBoOCOgHEQAAAAAAAABAGAUKDQoLCgdvcmRlcklkEAESBggLEBAYARIGCBEQCxgBEiAIEhAKGAEiGE9yZGVySGFkVW5hdmFpbGFibGVJdGVtcxIVCBIQDBgBIg1JdGVtc1Jlc2VydmVkEgYIAhALGAESBggGEBEYARIGCAgQFBgBEgYICBADGAESBggBEAkYARIGCAkQCxgBEgYIBRASGAESBggDEAUYARIGCAwQAhgBEgYIChAAGAESBggNEBMYARIZCAQQDhgBIhFTaGlwcGluZ0NvbmZpcm1lZBIGCA8QDRgBEhkIEBAPGAEiEVBheW1lbnRTdWNjZXNzZnVsEhUIEBAHGAEiDVBheW1lbnRGYWlsZWQSBggTEAQYARIGCBQQBRgBOhA5YTJmOGMyODgwZWE4ZmMw"),
      resources = Some(skuber.Resource.Requirements(
        requests = Map("cpu" -> skuber.Resource.Quantity("600m"), "memory" -> skuber.Resource.Quantity("500Mi")),
        limits = Map("cpu" -> skuber.Resource.Quantity("6000m"), "memory" -> skuber.Resource.Quantity("1000Mi"))
      )),
      config = None,
      secrets = None,
      apiLoggingEnabled = true,
      sidecar = Some(
        SidecarSpec(
          image = "bakery-baker:local",
          resources = Some(skuber.Resource.Requirements(
            requests = Map("cpu" -> skuber.Resource.Quantity("600m"), "memory" -> skuber.Resource.Quantity("500Mi")),
            limits = Map("cpu" -> skuber.Resource.Quantity("6000m"), "memory" -> skuber.Resource.Quantity("1000Mi"))
          )),
          environment = Some(Map(
            "POD_IP" -> "@status.podIP",
            "CLUSTER_DNS_SUFFIX" -> ".test.local"
          )),
          configVolumeMountPath = Some("/home/app/config"),
          readinessProbe = Some(skuber.Probe(
            action = skuber.HTTPGetAction(
              port = Right("8443"),
              path = "/metrics",
              schema = "https"
            ),
            initialDelaySeconds = 15,
            timeoutSeconds = 10
          )),
          livenessProbe = Some(
            skuber.Probe(
              action = skuber.HTTPGetAction(
                port = Right("8443"),
                path = "/metrics",
                schema = "https"
              ),
              initialDelaySeconds = 15,
              timeoutSeconds = 10
            )
          )
        )
      )
    )
  )

  val bakerConfigMapResourceSidecar: skuber.ConfigMap = skuber.ConfigMap(
    metadata = ObjectMeta(name = "RecipeOne"),
    data = Map(
      "image" -> "bakery-baker:local",
      "replicas" -> "2",
      "recipes.0" -> "CgdXZWJzaG9wErEQChYKFAoQdW5hdmFpbGFibGVJdGVtcxABCkVaQwo/ChdTaGlwcGluZ0FkZHJlc3NSZWNlaXZlZBIkCg9zaGlwcGluZ0FkZHJlc3MSESIPCg0KB2FkZHJlc3MSAggREAEKEwoRCg1yZXNlcnZlZEl0ZW1zEAEKCwoJCgVpdGVtcxABCg8KDQoJU2hpcEl0ZW1zEAIKnANimQMKRAoYT3JkZXJIYWRVbmF2YWlsYWJsZUl0ZW1zEigKEHVuYXZhaWxhYmxlSXRlbXMSFBoSChAiDgoMCgZpdGVtSWQSAggRCk8KDUl0ZW1zUmVzZXJ2ZWQSPgoNcmVzZXJ2ZWRJdGVtcxItIisKHQoFaXRlbXMSFBoSChAiDgoMCgZpdGVtSWQSAggRCgoKBGRhdGESAggWEkQKGE9yZGVySGFkVW5hdmFpbGFibGVJdGVtcxIoChB1bmF2YWlsYWJsZUl0ZW1zEhQaEgoQIg4KDAoGaXRlbUlkEgIIERJPCg1JdGVtc1Jlc2VydmVkEj4KDXJlc2VydmVkSXRlbXMSLSIrCh0KBWl0ZW1zEhQaEgoQIg4KDAoGaXRlbUlkEgIIEQoKCgRkYXRhEgIIFiIcCgdvcmRlcklkEhEiDwoNCgdvcmRlcklkEgIIESIdCgVpdGVtcxIUGhIKECIOCgwKBml0ZW1JZBICCBEqDFJlc2VydmVJdGVtczIMUmVzZXJ2ZUl0ZW1zUhAaDgjoBxEAAAAAAAAAQBgFCkhaRgpCChpQYXltZW50SW5mb3JtYXRpb25SZWNlaXZlZBIkChJwYXltZW50SW5mb3JtYXRpb24SDiIMCgoKBGluZm8SAggREAEKFVoTCg8KDVBheW1lbnRGYWlsZWQQAApQWk4KSgoLT3JkZXJQbGFjZWQSHAoHb3JkZXJJZBIRIg8KDQoHb3JkZXJJZBICCBESHQoFaXRlbXMSFBoSChAiDgoMCgZpdGVtSWQSAggREAEKFQoTCg9zaGlwcGluZ0FkZHJlc3MQAQpKWkgKRAoYT3JkZXJIYWRVbmF2YWlsYWJsZUl0ZW1zEigKEHVuYXZhaWxhYmxlSXRlbXMSFBoSChAiDgoMCgZpdGVtSWQSAggREAAK2wNi2AMKcQoRUGF5bWVudFN1Y2Nlc3NmdWwSXAoNc2hpcHBpbmdPcmRlchJLIkkKHQoFaXRlbXMSFBoSChAiDgoMCgZpdGVtSWQSAggRCgoKBGRhdGESAggWChwKB2FkZHJlc3MSESIPCg0KB2FkZHJlc3MSAggRCg8KDVBheW1lbnRGYWlsZWQScQoRUGF5bWVudFN1Y2Nlc3NmdWwSXAoNc2hpcHBpbmdPcmRlchJLIkkKHQoFaXRlbXMSFBoSChAiDgoMCgZpdGVtSWQSAggRCgoKBGRhdGESAggWChwKB2FkZHJlc3MSESIPCg0KB2FkZHJlc3MSAggREg8KDVBheW1lbnRGYWlsZWQiFgoQcmVjaXBlSW5zdGFuY2VJZBICCBEiPgoNcmVzZXJ2ZWRJdGVtcxItIisKHQoFaXRlbXMSFBoSChAiDgoMCgZpdGVtSWQSAggRCgoKBGRhdGESAggWIiQKD3NoaXBwaW5nQWRkcmVzcxIRIg8KDQoHYWRkcmVzcxICCBEiJAoScGF5bWVudEluZm9ybWF0aW9uEg4iDAoKCgRpbmZvEgIIESoLTWFrZVBheW1lbnQyC01ha2VQYXltZW50UhAaDgjoBxEAAAAAAAAAQBgFClVaUwpPCg1JdGVtc1Jlc2VydmVkEj4KDXJlc2VydmVkSXRlbXMSLSIrCh0KBWl0ZW1zEhQaEgoQIg4KDAoGaXRlbUlkEgIIEQoKCgRkYXRhEgIIFhAAChMKEQoNc2hpcHBpbmdPcmRlchABChlaFwoTChFTaGlwcGluZ0NvbmZpcm1lZBAACndadQpxChFQYXltZW50U3VjY2Vzc2Z1bBJcCg1zaGlwcGluZ09yZGVyEksiSQodCgVpdGVtcxIUGhIKECIOCgwKBml0ZW1JZBICCBEKCgoEZGF0YRICCBYKHAoHYWRkcmVzcxIRIg8KDQoHYWRkcmVzcxICCBEQAAoRCg8KC01ha2VQYXltZW50EAIKGAoWChJwYXltZW50SW5mb3JtYXRpb24QAQoSChAKDFJlc2VydmVJdGVtcxACCrMBYrABChMKEVNoaXBwaW5nQ29uZmlybWVkEhMKEVNoaXBwaW5nQ29uZmlybWVkIlwKDXNoaXBwaW5nT3JkZXISSyJJCh0KBWl0ZW1zEhQaEgoQIg4KDAoGaXRlbUlkEgIIEQoKCgRkYXRhEgIIFgocCgdhZGRyZXNzEhEiDwoNCgdhZGRyZXNzEgIIESoJU2hpcEl0ZW1zMglTaGlwSXRlbXNSEBoOCOgHEQAAAAAAAABAGAUKDQoLCgdvcmRlcklkEAESBggLEBAYARIGCBEQCxgBEiAIEhAKGAEiGE9yZGVySGFkVW5hdmFpbGFibGVJdGVtcxIVCBIQDBgBIg1JdGVtc1Jlc2VydmVkEgYIAhALGAESBggGEBEYARIGCAgQFBgBEgYICBADGAESBggBEAkYARIGCAkQCxgBEgYIBRASGAESBggDEAUYARIGCAwQAhgBEgYIChAAGAESBggNEBMYARIZCAQQDhgBIhFTaGlwcGluZ0NvbmZpcm1lZBIGCA8QDRgBEhkIEBAPGAEiEVBheW1lbnRTdWNjZXNzZnVsEhUIEBAHGAEiDVBheW1lbnRGYWlsZWQSBggTEAQYARIGCBQQBRgBOhA5YTJmOGMyODgwZWE4ZmMw",
      "resources.requests.cpu" -> "600m",
      "resources.requests.memory" -> "500Mi",
      "resources.limits.cpu" -> "6000m",
      "resources.limits.memory" -> "1000Mi",
      "sidecar.image" -> "bakery-baker:local",
      "sidecar.configVolumeMountPath" -> "/home/app/config",
      "sidecar.resources.requests.cpu" -> "600m",
      "sidecar.resources.requests.memory" -> "500Mi",
      "sidecar.resources.limits.cpu" -> "6000m",
      "sidecar.resources.limits.memory" -> "1000Mi",
      "sidecar.livenessProbe.scheme" -> "https",
      "sidecar.livenessProbe.port" -> "10080",
      "sidecar.livenessProbe.path" -> "/metrics",
      "sidecar.readinessProbe.scheme" -> "https",
      "sidecar.readinessProbe.port" -> "10080",
      "sidecar.readinessProbe.path" -> "/metrics",
      "sidecar.environment.POD_IP" -> "@status.podIP",
      "sidecar.environment.CLUSTER_DNS_SUFFIX" -> ".test.local"
    )
  )

}

class BakeryControllerSpec extends BakeryFunSpec with Matchers {

  describe("Interactions Controller (CRDs)") {

    test("Creates interactions (CRDs)") { context =>
      context.interactionController.use(_ =>
        for {
          _ <- context.kubeApiServer.expectCreationOf("expectations/interaction-deployment.json", ResourcePath.DeploymentsPath)
          _ <- context.kubeApiServer.expectCreationOf("expectations/interaction-service.json", ResourcePath.ServicesPath, context.adaptHttpPortToMockServerPort)
          _ <- context.kubeApiServer.expectCreationOf("expectations/interaction-creation-config-map.json", ResourcePath.ConfigMapsPath, context.adaptHttpPortToMockServerPort)
          _ <- context.kubeApiServer.createInteractions(interactionResource)
          _ <- context.remoteInteraction.publishesItsInterface(interaction)
          _ <- eventually(for {
            _ <- context.kubeApiServer.validateCreationOf(ResourcePath.DeploymentsPath)
            _ <- context.kubeApiServer.validateCreationOf(ResourcePath.ServicesPath)
            _ <- context.remoteInteraction.interfaceWasQueried(interaction)
            _ <- context.kubeApiServer.validateCreationOf(ResourcePath.ConfigMapsPath)
          } yield succeed)
        } yield succeed
      )
    }

    test("Creates interactions (With already previously created components)") { context =>
      context.interactionController.use(_ =>
        for {
          _ <- context.kubeApiServer.expectCreationOfAndReport409(ResourcePath.DeploymentsPath)
          _ <- context.kubeApiServer.expectCreationOfAndReport409(ResourcePath.ServicesPath)
          _ <- context.kubeApiServer.expectGetOf("expectations/interaction-service.json", ResourcePath.Named("localhost", ResourcePath.ServicesPath), context.adaptHttpPortToMockServerPort)
          _ <- context.kubeApiServer.expectCreationOf("expectations/interaction-creation-config-map.json", ResourcePath.ConfigMapsPath, context.adaptHttpPortToMockServerPort)
          _ <- context.kubeApiServer.createInteractions(interactionResource)
          _ <- context.remoteInteraction.publishesItsInterface(interaction)
          _ <- eventually(for {
            _ <- context.kubeApiServer.validateCreationOf(ResourcePath.DeploymentsPath)
            _ <- context.kubeApiServer.validateCreationOf(ResourcePath.ServicesPath)
            _ <- context.remoteInteraction.interfaceWasQueried(interaction)
            _ <- context.kubeApiServer.validateCreationOf(ResourcePath.ConfigMapsPath)
          } yield succeed)
        } yield succeed
      )
    }

    test("Deletes interactions (CRDs)") { context =>
      context.interactionController.use(_ =>
        for {
          _ <- context.kubeApiServer.expectDeletionOf(ResourcePath.Named("interactions-localhost", ResourcePath.ConfigMapsPath))
          _ <- context.kubeApiServer.expectDeletionOf(ResourcePath.Named("localhost", ResourcePath.DeploymentsPath))
          _ <- context.kubeApiServer.expectDeletionOf(ResourcePath.Named("localhost", ResourcePath.ServicesPath))
          _ <- context.kubeApiServer.expectDeletionOf(ResourcePath.ReplicaSetsPath, Some("bakery-interaction-name" -> "localhost"), Some("expectations/interaction-replicaset-deletion.json"))
          _ <- context.kubeApiServer.expectDeletionOf(ResourcePath.PodsPath, Some("bakery-interaction-name" -> "localhost"), Some("expectations/interaction-podlist-deletion.json"))
          _ <- context.kubeApiServer.deleteInteractions(interactionResource)
          _ <- eventually(for {
            _ <- context.kubeApiServer.validateDeletionOf(ResourcePath.Named("localhost-manifest", ResourcePath.ConfigMapsPath))
            _ <- context.kubeApiServer.validateDeletionOf(ResourcePath.Named("localhost", ResourcePath.DeploymentsPath))
            _ <- context.kubeApiServer.validateDeletionOf(ResourcePath.Named("localhost", ResourcePath.ServicesPath))
            _ <- context.kubeApiServer.validateDeletionOf(ResourcePath.ReplicaSetsPath, Some("bakery-interaction-name" -> "localhost"))
            _ <- context.kubeApiServer.validateDeletionOf(ResourcePath.PodsPath, Some("bakery-interaction-name" -> "localhost"))
          } yield succeed)
        } yield succeed
      )
    }

    test("Updates interactions (CRDs)") { context =>
      context.interactionController.use(_ =>
        for {
          _ <- context.kubeApiServer.expectUpdateOf("expectations/interaction-deployment.json", ResourcePath.Named("localhost", ResourcePath.DeploymentsPath))
          _ <- context.kubeApiServer.updateInteractions(interactionResource)
          _ <- eventually(for {
            _ <- context.kubeApiServer.validateUpdateOf(ResourcePath.Named("localhost", ResourcePath.DeploymentsPath))
          } yield succeed)
        } yield succeed
      )
    }
  }

  describe("Interactions Controller (Config Maps)") {

    test("Creates interactions (Config Maps)") { context =>
      context.interactionControllerConfigMaps.use(_ =>
        for {
          _ <- context.kubeApiServer.expectCreationOf("expectations/interaction-deployment.json", ResourcePath.DeploymentsPath)
          _ <- context.kubeApiServer.expectCreationOf("expectations/interaction-service.json", ResourcePath.ServicesPath, context.adaptHttpPortToMockServerPort)
          _ <- context.kubeApiServer.expectCreationOf("expectations/interaction-creation-config-map.json", ResourcePath.ConfigMapsPath, context.adaptHttpPortToMockServerPort)
          _ <- context.kubeApiServer.createConfigMapFor("interactions", interactionConfigMapResource)
          _ <- context.remoteInteraction.publishesItsInterface(interaction)
          _ <- eventually(for {
            _ <- context.kubeApiServer.validateCreationOf(ResourcePath.DeploymentsPath)
            _ <- context.kubeApiServer.validateCreationOf(ResourcePath.ServicesPath)
            _ <- context.remoteInteraction.interfaceWasQueried(interaction)
            _ <- context.kubeApiServer.validateCreationOf(ResourcePath.ConfigMapsPath)
          } yield succeed)
        } yield succeed
      )
    }

    test("Deletes interactions (Config Maps)") { context =>
      context.interactionControllerConfigMaps.use(_ =>
        for {
          _ <- context.kubeApiServer.expectDeletionOf(ResourcePath.Named("localhost-manifest", ResourcePath.ConfigMapsPath))
          _ <- context.kubeApiServer.expectDeletionOf(ResourcePath.Named("localhost", ResourcePath.DeploymentsPath))
          _ <- context.kubeApiServer.expectDeletionOf(ResourcePath.Named("localhost", ResourcePath.ServicesPath))
          _ <- context.kubeApiServer.expectDeletionOf(ResourcePath.ReplicaSetsPath, Some("bakery-interaction-name" -> "localhost"), Some("expectations/interaction-replicaset-deletion.json"))
          _ <- context.kubeApiServer.expectDeletionOf(ResourcePath.PodsPath, Some("bakery-interaction-name" -> "localhost"), Some("expectations/interaction-podlist-deletion.json"))
          _ <- context.kubeApiServer.deleteConfigMapFor("interactions", interactionConfigMapResource)
          _ <- eventually(for {
            _ <- context.kubeApiServer.validateDeletionOf(ResourcePath.Named("localhost-manifest", ResourcePath.ConfigMapsPath))
            _ <- context.kubeApiServer.validateDeletionOf(ResourcePath.Named("localhost", ResourcePath.DeploymentsPath))
            _ <- context.kubeApiServer.validateDeletionOf(ResourcePath.Named("localhost", ResourcePath.ServicesPath))
            _ <- context.kubeApiServer.validateDeletionOf(ResourcePath.ReplicaSetsPath, Some("bakery-interaction-name" -> "localhost"))
            _ <- context.kubeApiServer.validateDeletionOf(ResourcePath.PodsPath, Some("bakery-interaction-name" -> "localhost"))
          } yield succeed)
        } yield succeed
      )
    }

    test("Updates interactions (Config Maps)") { context =>
      context.interactionControllerConfigMaps.use(_ =>
        for {
          _ <- context.kubeApiServer.expectUpdateOf("expectations/interaction-deployment.json", ResourcePath.Named("localhost", ResourcePath.DeploymentsPath))
          _ <- context.kubeApiServer.updateConfigMapFor("interactions", interactionConfigMapResource)
          _ <- eventually(for {
            _ <- context.kubeApiServer.validateUpdateOf(ResourcePath.Named("localhost", ResourcePath.DeploymentsPath))
          } yield succeed)
        } yield succeed
      )
    }
  }

  describe("Bakers Controller (CRDs)") {

    test("Creates state nodes (CRDs)") { context =>
      context.bakerController.use(_ =>
        for {
          _ <- context.kubeApiServer.expectCreationOf("expectations/baker-deployment.json", ResourcePath.DeploymentsPath)
          _ <- context.kubeApiServer.expectCreationOf("expectations/baker-service.json", ResourcePath.ServicesPath, context.adaptHttpPortToMockServerPort)
          _ <- context.kubeApiServer.expectCreationOf("expectations/baker-creation-recipes.json", ResourcePath.ConfigMapsPath, context.adaptHttpPortToMockServerPort)
          _ <- context.kubeApiServer.createBakers(bakerResource)
          _ <- eventually(for {
            _ <- context.kubeApiServer.validateCreationOf(ResourcePath.DeploymentsPath)
            _ <- context.kubeApiServer.validateCreationOf(ResourcePath.ServicesPath)
            _ <- context.kubeApiServer.validateCreationOf(ResourcePath.ConfigMapsPath)
          } yield succeed)
        } yield succeed
      )
    }

    test("Creates state nodes with sidecars (CRDs)") { context =>
      context.bakerController.use(_ =>
        for {
          _ <- context.kubeApiServer.expectCreationOf("expectations/baker-deployment-sidecar.json", ResourcePath.DeploymentsPath)
          _ <- context.kubeApiServer.expectCreationOf("expectations/baker-service.json", ResourcePath.ServicesPath, context.adaptHttpPortToMockServerPort)
          _ <- context.kubeApiServer.expectCreationOf("expectations/baker-creation-recipes.json", ResourcePath.ConfigMapsPath, context.adaptHttpPortToMockServerPort)
          _ <- context.kubeApiServer.createBakers(bakerResourceSidecar)
          _ <- eventually(for {
            _ <- context.kubeApiServer.validateCreationOf(ResourcePath.DeploymentsPath)
            _ <- context.kubeApiServer.validateCreationOf(ResourcePath.ServicesPath)
            _ <- context.kubeApiServer.validateCreationOf(ResourcePath.ConfigMapsPath)
          } yield succeed)
        } yield succeed
      )
    }


    test("Creates state nodes (With already created components)") { context =>
      context.bakerController.use(_ =>
        for {
          _ <- context.kubeApiServer.expectCreationOfAndReport409(ResourcePath.DeploymentsPath)
          _ <- context.kubeApiServer.expectCreationOfAndReport409(ResourcePath.ServicesPath)
          _ <- context.kubeApiServer.expectCreationOf("expectations/baker-creation-recipes.json", ResourcePath.ConfigMapsPath, context.adaptHttpPortToMockServerPort)
          _ <- context.kubeApiServer.createBakers(bakerResource)
          _ <- eventually(for {
            _ <- context.kubeApiServer.validateCreationOf(ResourcePath.DeploymentsPath)
            _ <- context.kubeApiServer.validateCreationOf(ResourcePath.ServicesPath)
            _ <- context.kubeApiServer.validateCreationOf(ResourcePath.ConfigMapsPath)
          } yield succeed)
        } yield succeed
      )
    }

    test("Deletes state nodes (CRDs)") { context =>
      context.bakerController.use(_ =>
        for {
          _ <- context.kubeApiServer.expectDeletionOf(ResourcePath.Named("RecipeOne-manifest", ResourcePath.ConfigMapsPath))
          _ <- context.kubeApiServer.expectDeletionOf(ResourcePath.Named("RecipeOne", ResourcePath.DeploymentsPath))
          _ <- context.kubeApiServer.expectDeletionOf(ResourcePath.Named("RecipeOne", ResourcePath.ServicesPath))
          _ <- context.kubeApiServer.expectDeletionOf(ResourcePath.ReplicaSetsPath, Some("bakery-baker-name" -> "RecipeOne"), Some("expectations/interaction-replicaset-deletion.json"))
          _ <- context.kubeApiServer.expectDeletionOf(ResourcePath.PodsPath, Some("bakery-baker-name" -> "RecipeOne"), Some("expectations/interaction-podlist-deletion.json"))
          _ <- context.kubeApiServer.deleteBakers(bakerResource)
          _ <- eventually(for {
            _ <- context.kubeApiServer.validateDeletionOf(ResourcePath.Named("RecipeOne-manifest", ResourcePath.ConfigMapsPath))
            _ <- context.kubeApiServer.validateDeletionOf(ResourcePath.Named("RecipeOne", ResourcePath.DeploymentsPath))
            _ <- context.kubeApiServer.validateDeletionOf(ResourcePath.Named("RecipeOne", ResourcePath.ServicesPath))
            _ <- context.kubeApiServer.validateDeletionOf(ResourcePath.ReplicaSetsPath, Some("bakery-baker-name" -> "RecipeOne"))
            _ <- context.kubeApiServer.validateDeletionOf(ResourcePath.PodsPath, Some("bakery-baker-name" -> "RecipeOne"))
          } yield succeed)
        } yield succeed
      )
    }

    test("Updates state nodes (CRDs)") { context =>
      context.bakerController.use(_ =>
        for {
          _ <- context.kubeApiServer.expectUpdateOf("expectations/baker-creation-recipes.json", ResourcePath.Named("RecipeOne-manifest", ResourcePath.ConfigMapsPath))
          _ <- context.kubeApiServer.expectUpdateOf("expectations/baker-deployment.json", ResourcePath.Named("RecipeOne", ResourcePath.DeploymentsPath))
          _ <- context.kubeApiServer.updateBakers(bakerResource)
          _ <- eventually(for {
            _ <- context.kubeApiServer.validateUpdateOf(ResourcePath.Named("RecipeOne", ResourcePath.DeploymentsPath))
            _ <- context.kubeApiServer.validateUpdateOf(ResourcePath.Named("RecipeOne-manifest", ResourcePath.ConfigMapsPath))
          } yield succeed)
        } yield succeed
      )
    }
  }

  describe("Bakers Controller (Config Maps)") {

    test("Creates state nodes (Config Maps)") { context =>
      context.bakerControllerConfigMaps.use(_ =>
        for {
          _ <- context.kubeApiServer.expectCreationOf("expectations/baker-deployment.json", ResourcePath.DeploymentsPath)
          _ <- context.kubeApiServer.expectCreationOf("expectations/baker-service.json", ResourcePath.ServicesPath, context.adaptHttpPortToMockServerPort)
          _ <- context.kubeApiServer.expectCreationOf("expectations/baker-creation-recipes.json", ResourcePath.ConfigMapsPath, context.adaptHttpPortToMockServerPort)
          _ <- context.kubeApiServer.createConfigMapFor("bakers", bakerConfigMapResource)
          _ <- eventually(for {
            _ <- context.kubeApiServer.validateCreationOf(ResourcePath.DeploymentsPath)
            _ <- context.kubeApiServer.validateCreationOf(ResourcePath.ServicesPath)
            _ <- context.kubeApiServer.validateCreationOf(ResourcePath.ConfigMapsPath)
          } yield succeed)
        } yield succeed
      )
    }

    test("Creates state nodes with sidecar (Config Maps)") { context =>
      context.bakerControllerConfigMaps.use(_ =>
        for {
          _ <- context.kubeApiServer.expectCreationOf("expectations/baker-deployment-sidecar.json", ResourcePath.DeploymentsPath)
          _ <- context.kubeApiServer.expectCreationOf("expectations/baker-service.json", ResourcePath.ServicesPath, context.adaptHttpPortToMockServerPort)
          _ <- context.kubeApiServer.expectCreationOf("expectations/baker-creation-recipes.json", ResourcePath.ConfigMapsPath, context.adaptHttpPortToMockServerPort)
          _ <- context.kubeApiServer.createConfigMapFor("bakers", bakerConfigMapResourceSidecar)
          _ <- eventually(for {
            _ <- context.kubeApiServer.validateCreationOf(ResourcePath.DeploymentsPath)
            _ <- context.kubeApiServer.validateCreationOf(ResourcePath.ServicesPath)
            _ <- context.kubeApiServer.validateCreationOf(ResourcePath.ConfigMapsPath)
          } yield succeed)
        } yield succeed
      )
    }
    test("Deletes state nodes (Config Maps)") { context =>
      context.bakerControllerConfigMaps.use(_ =>
        for {
          _ <- context.kubeApiServer.expectDeletionOf(ResourcePath.Named("RecipeOne-manifest", ResourcePath.ConfigMapsPath))
          _ <- context.kubeApiServer.expectDeletionOf(ResourcePath.Named("RecipeOne", ResourcePath.DeploymentsPath))
          _ <- context.kubeApiServer.expectDeletionOf(ResourcePath.Named("RecipeOne", ResourcePath.ServicesPath))
          _ <- context.kubeApiServer.expectDeletionOf(ResourcePath.ReplicaSetsPath, Some("bakery-baker-name" -> "RecipeOne"), Some("expectations/interaction-replicaset-deletion.json"))
          _ <- context.kubeApiServer.expectDeletionOf(ResourcePath.PodsPath, Some("bakery-baker-name" -> "RecipeOne"), Some("expectations/interaction-podlist-deletion.json"))
          _ <- context.kubeApiServer.deleteConfigMapFor("bakers", bakerConfigMapResource)
          _ <- eventually(for {
            _ <- context.kubeApiServer.validateDeletionOf(ResourcePath.Named("RecipeOne-manifest", ResourcePath.ConfigMapsPath))
            _ <- context.kubeApiServer.validateDeletionOf(ResourcePath.Named("RecipeOne", ResourcePath.DeploymentsPath))
            _ <- context.kubeApiServer.validateDeletionOf(ResourcePath.Named("RecipeOne", ResourcePath.ServicesPath))
            _ <- context.kubeApiServer.validateDeletionOf(ResourcePath.ReplicaSetsPath, Some("bakery-baker-name" -> "RecipeOne"))
            _ <- context.kubeApiServer.validateDeletionOf(ResourcePath.PodsPath, Some("bakery-baker-name" -> "RecipeOne"))
          } yield succeed)
        } yield succeed
      )
    }

    test("Updates state nodes (Config Maps)") { context =>
      context.bakerControllerConfigMaps.use(_ =>
        for {
          _ <- context.kubeApiServer.expectUpdateOf("expectations/baker-creation-recipes.json", ResourcePath.Named("RecipeOne-manifest", ResourcePath.ConfigMapsPath))
          _ <- context.kubeApiServer.expectUpdateOf("expectations/baker-deployment.json", ResourcePath.Named("RecipeOne", ResourcePath.DeploymentsPath))
          _ <- context.kubeApiServer.updateConfigMapFor("bakers", bakerConfigMapResource)
          _ <- eventually(for {
            _ <- context.kubeApiServer.validateUpdateOf(ResourcePath.Named("RecipeOne", ResourcePath.DeploymentsPath))
            _ <- context.kubeApiServer.validateUpdateOf(ResourcePath.Named("RecipeOne-manifest", ResourcePath.ConfigMapsPath))
          } yield succeed)
        } yield succeed
      )
    }
  }

  case class Context(
                      kubeApiServer: KubeApiServer,
                      remoteInteraction: RemoteInteraction,
                      adaptHttpPortToMockServerPort: String => String,
                      bakerController: Resource[IO, Unit],
                      interactionController: Resource[IO, Unit],
                      bakerControllerConfigMaps: Resource[IO, Unit],
                      interactionControllerConfigMaps: Resource[IO, Unit]
                    )

  /** Represents the "sealed resources context" that each test can use. */
  type TestContext = Context

  /** Represents external arguments to the test context builder. */
  type TestArguments = Unit

  /** Creates a `Resource` which allocates and liberates the expensive resources each test can use.
    * For example web servers, network connection, database mocks.
    *
    * The objective of this function is to provide "sealed resources context" to each test, that means context
    * that other tests simply cannot touch.
    *
    * @param testArguments arguments built by the `argumentsBuilder` function.
    * @return the resources each test can use
    */
  def contextBuilder(testArguments: TestArguments): Resource[IO, TestContext] =
    for {
      // Mock server
      mockServer <- Resource.make(IO(ClientAndServer.startClientAndServer(0)))(s => IO(s.stop()))
      kubeApiServer = new KubeApiServer(mockServer)

      makeActorSystem = IO {
        ActorSystem(UUID.randomUUID().toString, ConfigFactory.parseString(
          """
            |akka {
            |  stdout-loglevel = "OFF"
            |  loglevel = "OFF"
            |}
            |""".stripMargin))
      }
      stopActorSystem = (system: ActorSystem) => IO.fromFuture(IO {
        system.terminate().flatMap(_ => system.whenTerminated)
      }).void
      system <- Resource.make(makeActorSystem)(stopActorSystem)
      k8s: KubernetesClient = {
        implicit val sys = system
        skuber.k8sInit(skuber.api.Configuration.useLocalProxyOnPort(mockServer.getLocalPort))
      }
      cache <- Resource.liftF(ConfigMapDeploymentRelationCache.build)
    } yield {
      implicit val as: ActorSystem = system
      import InteractionResource.interactionResourceFormat
      import InteractionResource.resourceDefinitionInteractionResource

      val interactionController =
        Resource.liftF(kubeApiServer.noNewInteractionEvents).flatMap(_ => new InteractionController(executionContext).watch(k8s))
      val bakerController =
        Resource.liftF(kubeApiServer.noNewBakerEvents).flatMap(_ =>
          new BakerController(cache).watch(k8s))
      val interactionControllerConfigMaps =
        Resource.liftF(kubeApiServer.noNewConfigMapEventsFor("interactions")).flatMap(_ =>
          new InteractionController(executionContext).fromConfigMaps(InteractionResource.fromConfigMap).watch(k8s, label = Some("custom-resource-definition" -> "interactions")))
      val bakerControllerConfigMaps =
        Resource.liftF(kubeApiServer.noNewConfigMapEventsFor("bakers")).flatMap(_ =>
          new BakerController(cache).fromConfigMaps(BakerResource.fromConfigMap).watch(k8s, label = Some("custom-resource-definition" -> "bakers")))

      Context(
        kubeApiServer,
        new RemoteInteraction(mockServer),
        _.replace("{{http-api-port}}", mockServer.getLocalPort.toString),
        bakerController,
        interactionController,
        bakerControllerConfigMaps,
        interactionControllerConfigMaps
      )
    }

  /** Refines the `ConfigMap` populated with the -Dkey=value arguments coming from the "sbt testOnly" command.
    *
    * @param config map populated with the -Dkey=value arguments.
    * @return the data structure used by the `contextBuilder` function.
    */
  def argumentsBuilder(config: ConfigMap): TestArguments = ()
}
