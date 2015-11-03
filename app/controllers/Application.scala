package controllers

import com.madgag.github.Implicits._
import com.madgag.playgithub.auth.GHRequest
import lib.Bot
import lib.Permissions.allowedToCreatePrivateRepos
import lib.actions.Actions._
import lib.scalagithub.CreateRepo
import play.api.Play.current
import play.api.data.Form
import play.api.data.Forms._
import play.api.i18n.Messages.Implicits._
import play.api.mvc._

import scala.collection.convert.wrapAsScala._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object Application extends Controller {

  def about = Action { implicit req =>
    Ok(views.html.about())
  }

  case class RepoCreation(name: String, teamId: Long, isPrivate: Boolean)

  val repoCreationForm = Form(mapping(
    "name" -> text(minLength = 1, maxLength = 100),
    "teamId" -> longNumber,
    "private" -> boolean
  )(RepoCreation.apply)(RepoCreation.unapply))

  case class Team(id: Long, name: String, size: Int)

  def newRepo = OrgAuthenticated.async { implicit req =>
    for (allowPrivate <- allowedToCreatePrivateRepos(req.user)) yield {
      val teams = req.gitHub.getMyTeams.get(Bot.org).map(t => Team(t.getId, t.getName, t.getMembers.size)).toSeq.sortBy(_.size)
      Ok(views.html.createNewRepo(repoCreationForm, teams, allowPrivate))
    }
  }

  def createRepo = OrgAuthenticated.async(parse.form(repoCreationForm)) { implicit req =>
    for {
      allowPrivate <- allowedToCreatePrivateRepos(req.user)
      result <- barg(req, allowPrivate)
    } yield result
  }

  def barg(req: GHRequest[RepoCreation], allowPrivate: Boolean): Future[Result] = {
    val repoCreation = req.body
    if (repoCreation.isPrivate && !allowPrivate) {
      Future(Forbidden(s"${req.user.atLogin} is not currently allowed to create private repos"))
    } else for {
      createdRepo <- Bot.neoGitHub.createOrgRepo(Bot.org, CreateRepo(name = repoCreation.name, `private` = repoCreation.isPrivate))
      _ <- Bot.neoGitHub.addTeamRepo(repoCreation.teamId, Bot.org, repoCreation.name)
    } yield Redirect(createdRepo.result.collaborationSettingsUrl)
  }
}
